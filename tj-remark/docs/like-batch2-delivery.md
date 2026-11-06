# 点赞第二批交付记录（2026-09-28）

## 状态

本地实现与单元测试完成；服务器 Redis 容量测量和 RabbitMQ 队列调查完成。**B1/B2/B4 的真实环境演练已由独立核验方（丁香）在 2026-09-28 执行完毕并通过**，见文末《真实环境验收结果》。第二批已通过验收。

## 改动清单

| 项 | 文件与位置 | 改动 |
|---|---|---|
| B1、B5-3、B5-4 | `src/main/java/com/tianji/remark/task/LikeMetricsTask.java:32` | 独立于消费者每秒读取 Redis 状态；30 秒一条 `like.metrics` INFO，阈值越界输出一次 `ALERT`；采集 NOTE 兜底队列长度，识别已 ACK 未 XDEL 残留。只执行读取，不修复数据。 |
| B1 | `src/main/java/com/tianji/remark/task/LikeWriteBehindTask.java:125` | 记录本实例发布或落库失败次数与最近一次 DB+MQ 成功时间。 |
| B2 | `src/main/java/com/tianji/remark/task/LikeReconcileTask.java:36` | 5 分钟抽样近期 `liked_record` 用户，比较 Redis Set/计数与 MySQL；只输出 `ALERT[reconcile]`。比对前后要求 `READY`、`XLEN=0` 且 Stream `last-generated-id` 不变。 |
| B3 | `src/main/java/com/tianji/remark/task/LikeCacheInitializer.java:85` | 初始化读取 Redis 超时输出 `ALERT` 并保留失败状态；收窄已触及代码的异常捕获。既有四处对外 Redis 读写仍只把 `DataAccessException` 译为 503。 |
| B4 | `src/main/java/com/tianji/remark/service/LikeCacheService.java:41`、`src/main/java/com/tianji/remark/task/LikeMetricsTask.java:74` | 选方案 A：Lua `1/0/-1` 不变。每秒监控更新内存拒写标志，写接口预检；超过 50,000 条拒写，低于 40,000 条恢复；监控样本超过 2 秒未更新也拒写。已有 Stream 记录由消费者继续处理。 |
| B5-1 | `src/main/java/com/tianji/remark/task/LikeWriteBehindTask.java:54`、`:391` | 死信退避表最多 1,000 项，成功入死信立即移除；超限淘汰最早加入的退避状态，**不删除原 Stream/PEL 记录**，下轮仍可重试。恢复期走完一轮 PEL 后可识别已尝试但标记被淘汰的坏记录，避免卡死启动。 |
| 调度 | `src/main/resources/bootstrap.yml:6` | 定时任务线程池设为 4，避免 MQ 队列探测或对账阻塞 1 秒消费任务。 |

没有新增应用 Redis key，既有 key 名与类型、对外 URL/参数/响应结构均未改动；没有引入依赖或自动修复动作。测试用临时 key `remark:like:measure:batch2:<uuid>` 已删除。

配置默认值：`tj.like.metrics.poll-ms=1000`、`tj.like.metrics.log-interval-ms=30000`、`tj.like.alert.pending-threshold=1000`、`tj.like.alert.lag-ms-threshold=60000`、`tj.like.alert.ready-stale-ms=30000`、`tj.like.alert.residual-stale-ms=300000`、`tj.like.alert.note-queue-threshold=1000`、`tj.like.reconcile.interval-ms=300000`、`tj.like.reconcile.sample-users=50`、`tj.like.backlog.high-watermark=50000`、`tj.like.backlog.sample-stale-ms=2000`。

## B4 容量实测与水位推导

在文档指定服务器的 Redis 6.2.6 上，用独立临时 Stream 写入与生产事件相同的六个字段；两次试验的 key 都已 `DEL`（输出 `cleanup=1`）。业务 Stream `remark:like:changes` 测前 `XLEN=0`，未用于灌数。

| 试验 | 条数 | `INFO memory used_memory` | 相对同次基线增量 | `MEMORY USAGE` 临时 Stream | 每条 key 字节 |
|---|---:|---:|---:|---:|---:|
| 复核 1 | 2,000 | 未记录 | — | 106,079 B | 53.04 B |
| 复核 1 | 10,000 | 未记录 | — | 525,945 B | 52.59 B |
| 复核 2 基线 | 0 | 4,686,376 B | 0 | 0 | — |
| 复核 2 | 2,000 | 4,704,544 B | 18,168 B | 106,796 B | 53.40 B |
| 复核 2 | 10,000 | 5,172,216 B | 485,840 B | 528,029 B | 52.80 B |

2,000 条的全局 `used_memory` 增量小于临时 key 用量，说明共享实例同期有其他 key 变化；水位按更稳定的 `MEMORY USAGE` 的约 53 B/条计算。2,000→10,000 条时 key 用量约增长 4.94 倍，接近消息数的 5 倍。

宿主 `free -m`：可用 **1,802 MiB**，swap **0**，共 10 个运行容器；Redis `maxmemory=0`、`maxmemory_policy=noeviction`。只允许点赞积压占当时宿主可用量的 **0.5%**，即 `1802 × 2^20 × 0.005 = 9,447,670 B`；再以实测 `52.8029 × 3 = 158.4087 B/条` 计入结构波动、碎片与其他点赞键，得到约 **59,641 条**。向下取整为 **50,000 条**，保守预算约 **7,920,435 B**；恢复线为 80%，即 `XLEN < 40,000`。

方案 A 的预检与 Lua 非原子，50,000 是**软水位**：最多还会接收采样间隔及在途请求中的变更。监控超过 2 秒没有成功采样即拒写；实际超量还需用峰值写入速率测出。拒写 503 本身不会执行 Lua/XADD。未修改 Redis 全局 `maxmemory`：这是共享实例，直接设置会影响其他服务，需单独评估。

## B5 现场调查与已知边界

- `remark.note.liked.times.queue`：`/tjxt` vhost 当前 1 条 ready、0 个消费者；B1 已采集消息数，超过 1,000 条告警。
- `qa.liked.times.queue`：当前 **17** 条 ready、0 条 unacknowledged、**0** 个消费者；与 `learning.qa.liked.times.queue` 一样绑定在 `like.record.topic` 的 `QA.times.changed`。后者 0 条 ready、1 个消费者。代码只声明后者；前者是遗留队列，当前路由仍会把 QA 快照复制进去。谁创建了前者、17 条各自的年代未核实；**未 purge、未解绑**。
- `retrySnapshots` 仍是进程内 Map，重启会丢。**B2 只比 Redis 与 remark MySQL；两者一致但下游 MQ 快照漏发时，B2 无法发现。** 原规格 B5-2 写的“重启后可由 B2 对账发现差异”在这种情况下不成立，需要第三批跨服务对账或持久化补发设计。
- B2 默认抽最新主键顺序的最多 200 行，去重为最多 50 个用户；每轮最多比对 50 个业务对象的计数。这样避免 Redis 全量扫描和无索引时间排序。Redis 中只有孤立成员、MySQL 已无任何该用户记录的用户可能不在样本里；演练“Redis 假成员”应放在已入样本的现有用户 key 中。
- 残留仅告警。人工 `XTRIM MINID` 前必须确认该 Stream 只有本服务一个 group、`XPENDING=0`、`XINFO GROUPS` 的 `last-delivered-id` 之后没有未投递记录；以 `last-delivered-id` 的序号加一作为 **MINID 截止值**，仅清除该 ID 及之前的已确认记录。操作前保存 `XINFO`/`XRANGE` 证据，且不得自动执行。

## 测试先红后绿

均使用 JDK 11，命令为 `mvn -pl tj-remark -am -Dtest=<测试类> -Dsurefire.failIfNoSpecifiedTests=false test`。以下是 Maven 实际结果摘录：

| 项 | 实现前失败 | 实现后通过 |
|---|---|---|
| B1/B2 | `Tests run: 3, Failures: 2`；周期日志预期 2 条、实际 0；Redis 假成员预期告警、实际无告警 | `Tests run: 3, Failures: 0, Errors: 0`；后续增加边界测试 |
| B3 | `Tests run: 1, Failures: 1`；初始化 Redis 超时缺少 `ALERT` | `Tests run: 1, Failures: 0, Errors: 0` |
| B4/B5-1 | `Tests run: 22, Failures: 2`；超水位请求未抛 503；退避表预期 1000 项、实际 1001 项 | `Tests run: 22, Failures: 0, Errors: 0`；方案 A 定稿后另跑 15 项通过 |
| B5-1 恢复边界 | `Tests run: 12, Failures: 1`；退避标记淘汰后恢复判据预期 true、实际 false | `Tests run: 12, Failures: 0, Errors: 0` |

既有 B3 读取测试使用真实 `LettuceExceptionConverter` 把命令超时译为 `QueryTimeoutException`，并断言 503；编程错误原样抛出。`/likes/list` 在当前 controller 中不存在。B5-3/B5-4 的残留与 NOTE 队列边界测试随 B1 监控类补齐，属于同一组红绿周期；新增的后续边界断言没有分别保留实现前的独立失败输出。

最终执行 `mvn -pl tj-remark -am test`：`Tests run: 41, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。相对第一批的 27 项，新增 **14** 项。

## 真实环境验收结果（2026-09-28，独立核验方执行）

环境：`tj-remark` local profile，Windows 侧 8091；Redis 6.2.6 / MySQL / RabbitMQ 在阿里云 ECS（经 SSH 隧道）。
方法：所有判据**轮询到收敛**再判定，不对 `XLEN`/`PENDING` 这类中间态做一次性快照（第一批曾因此三次误报）。
为验 B4 期间临时以 `-Dtj.like.backlog.high-watermark=20` 启动，验完撤销。

| # | 验收项 | 判据 | 结果 | 实测证据 |
|---|---|---|---|---|
| 1 | 正常态观测 | 30 秒一条 INFO、字段完整、无 ALERT | ✅ | `16:16:17 → 16:16:46`（间隔 29s）：`like.metrics xlen=0 pending=0 lagMs=0 failCount=0 lastSuccessAt=- ready=READY noteQueue=1`；ALERT 无。日志线程出现 `scheduling-1/2/3`，证明 `bootstrap.yml` 的调度池 size=4 生效 |
| 2 | 非 READY 告警 | 超过 30s | ✅ | `16:17:53 ALERT 点赞缓存非 READY 持续 30353ms state=BUILDING`（仅一次） |
| 3 | 落库延迟告警 | 超过 60s | ✅ | `16:18:24 ALERT 点赞落库延迟超阈值 lagMs=60194 阈值=60000`（仅一次）；期间 INFO 的 lagMs 24203 → 54517 平滑增长 |
| 4 | 恢复后消费接管 | XLEN/PENDING 归零 + 落库 + ALERT 停 | ✅ | `XLEN=10 PEND=10 → XLEN=0 PEND=0`；MySQL user 9999 恰好 10 行（999101~999110）；恢复后无新 ALERT |
| 5 | 对账发现差异 | 具体 bizId + 两侧值，不自动修复 | ✅ | `16:21:21 ALERT[reconcile] 点赞计数差异 bizType=QA bizId=999104 redis=0.0 mysql=1`；`点赞关系差异 bizType=QA userId=9999 bizId=999105 redis=false mysql=true`；差异保留未修 |
| 6 | B4 超水位拒写 | 503 + 不写 Stream + 自动恢复 | ✅ | 停消费灌 25 条（水位 20）→ `POST → 503`，响应体「点赞积压或监控状态过期，暂时拒绝新请求」；B4 预检先于 Lua，故文案可区分于“未 READY 拒写”；`XLEN` 维持 25 未被写入；消费清空后 `POST → 200` |
| 7 | B4 拒写告警 | 越界即 ALERT | ✅ | `16:21:54 ALERT 点赞积压拒写 xlen=25 阈值=20`（仅一次） |
| 8 | B5-4 NOTE 队列采集 | noteQueue 有值 | ✅ | `noteQueue=1`（`remark.note.liked.times.queue` ready=1、consumers=0） |
| 9 | B3 Redis 超时 → 503 | 超时→503、编程错误不转译 | 单测覆盖 | 不做共享 Redis 断连演练（会波及其余 11 个服务）；`LikeCacheServiceTest` 用真实 `LettuceExceptionConverter` 断言 |
| 10 | 对账不误报中间态 | XLEN>0 或非 READY 时静默 | 代码 + 单测 | `LikeReconcileTask:38-44` 双重前置检查，且比对前后 `lastGeneratedId` 一致才输出；本轮积压窗口与对账周期（5min）未重叠，无实测样本，属已知验证缺口 |

**验收期副作用（已处置 / 待处置）**

- **已清理**：为验 B1/B4 向业务 Stream 灌入 35 条合法记录（999101~999110、999301~999325）+ 1 条 B4 成功写入（999999），落库后按 `user_id=9999` 全删（36 行），Redis 侧 `DEL`/`ZREM`/`HDEL` 同步清理，`liked_record` 已回到 `user_id=2` 单行基线。
- **待人工处置**：上述 36 条经 MQ 发布进入遗留队列 `qa.liked.times.queue`（无消费者），**17 → 53 条**。未 purge，待确认用途后由人工处置。
- 新增观察：**B4 预检在 Lua 之前**意味着一旦监控连续 2 秒采不到样（如 Redis 读失败），写接口会整体 503 —— 属于把可观测性故障升级为业务故障的取舍，真实流量下需关注 `ALERT 点赞积压监控状态过期`；启动瞬间（首次采样前）同样拒写，持续时间约 1 秒。
