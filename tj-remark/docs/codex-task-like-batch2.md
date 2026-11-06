# 第二批任务规格：监控、对账、降级与积压保护

> 目标读者：执行方（Codex）。本文只定义**边界、任务项、验收判据**，具体实现方式由你决定，但**硬约束（§1）不可协商**。
> 前置：第一批 P0–P3 已落地并通过真实环境验收，结果见 `docs/like-p1p3-acceptance.md`（388 行，含 3 条新发现与 6 条设计边界）。

---

## 0. 开工前必须确认的现场事实（基线，勿凭记忆）

| 项 | 现值 | 影响 |
|---|---|---|
| Redis 版本 | **6.2.6** | 无 `XAUTOCLAIM`，接管 pending 只能用 `XCLAIM` |
| Redis 持久化 | `appendonly yes` + `appendfsync everysec` | 断电窗口 ≤1s |
| Redis `maxmemory` | **未设置** | ⚠️ 积压无上限 → 可能 OOM 杀掉整个 Redis 实例（B4 的存在理由） |
| 写入 Lua 返回码 | **1 = 状态改变 / 0 = 幂等无变化 / -1 = 未 READY** | 已与调用方约定，**尽量不改**（见 B4 的方案选择） |
| 唯一重建入口 | `LikeCacheInitializer.run()`（`ApplicationRunner`，**仅启动时**执行） | `READY` 丢失且不重启 → 永久 503（第一批发现的 🟡） |
| 定时消费 | `LikeWriteBehindTask.flushChangesAndPublish()`，`fixedDelay=1000ms` | 观测打点的天然节拍 |
| consumer 名 | `WORKER_NAME = "remark-like-writer"`（**固定常量**） | 崩溃重启后同名 consumer 能读回自己的 PEL |
| 项目观测设施 | **无**（无 micrometer/actuator/prometheus，`tj-remark/pom.xml` 已核实） | 观测**只能用结构化日志**，见 B1 |
| 现有配置项风格 | `@Value("${tj.like.retry.backoff-base-ms:1000}")` | 新增阈值配置沿用 `tj.like.*` 前缀 + 默认值 |

**验收用的既有工具**（第一批留下，可复用）：服务器 `/tmp/like-change.lua`（生产 Lua 的副本）+ redis 容器内同名文件，可用 `redis-cli --eval` 在**服务停着**时造出四者齐全（Set/ZSet/Version/Stream）的状态。

---

## 1. 硬约束（违反即返工）

1. **不改对外接口**：`LikedRecordController` 的 URL / 参数 / 响应体结构一律不动。若确需观测端点，只能放 `/internal/**` 且**默认关闭**（配置开启）。
2. **不改既有 Redis 键的名字与类型**：`remark:like:user:*`（Set）/ `remark:like:count:*`（ZSet）/ `remark:like:version:*`（Hash）/ `remark:like:changes`（Stream）/ `remark:like:cache:ready`（String）保持不变。新增的辅助 key 必须**独立命名**，并在交付文档里登记。
3. **本批不引入任何新依赖**（含但不限于 Prometheus / micrometer / actuator / 邮件 / 钉钉 / 企业微信）。“能告警”的含义是：**日志里出现可被抓取的 `ALERT` 行**，不是接通告警通道。
4. **不做任何自动修复**：对账差异、残留记录、非 READY 状态 —— 一律**只报告**（`log.error("ALERT ...")`），**绝不自动**改 Redis / 改 MySQL / 重建缓存 / `XTRIM`。
5. **降级语义不得扩散**：不允许把“读失败返回空值/0/false”当作正常路径；不允许为了“让请求不报错”而吞掉异常。
6. **不用 `catch (Exception)`**：只捕获可预期的具体异常类型（第一批 P3-2 已按此整改 `LikeCacheService` 的 4 处，本批延续）。
7. **测试先行**：每一项在改实现之前，必须先写出能复现问题的测试并看到它**失败**；交付时贴“先失败 / 后通过”两份输出。
8. **改动最小**：能改几个数字/加一个判断解决的，不许重构类结构；不许顺手统一行尾符或格式化无关文件。

---

## 2. 任务分解

### B1 可观测指标（日志式，零依赖）

**要回答的问题**：现在积压多少？落后多久？失败几次？最后一次成功是什么时候？

**打点清单**（每轮 `flushChangesAndPublish` 收尾统计，**不要每轮都打日志**）：

| 指标 | 取法 | 说明 |
|---|---|---|
| `xlen` | `XLEN remark:like:changes` | 待消费总长度（含已投递未 ACK） |
| `pending` | `XPENDING <stream> <group>` 首行总数 | 已投递未 ACK |
| `lagMs` | `now - <Stream 里最小未落库 ID 的时间戳>` | **最有价值的指标**：消息在 Stream 里停留了多久 |
| `failCount` | 计数器（发布失败 + 落库失败） | 累计值，重启归零可接受 |
| `lastSuccessAt` | 最近一次整批落库 + MQ 发布都成功的时间 | 重启归零可接受 |
| `readyState` | `GET remark:like:cache:ready` 的值 | `READY` / `BUILDING` / 空 |

**两条输出通道**：

1. **周期性 INFO**（默认每 30 秒一条，配置 `tj.like.metrics.log-interval-ms:30000`）：
   ```
   like.metrics xlen=0 pending=0 lagMs=0 failCount=0 lastSuccessAt=15:05:00 ready=READY
   ```
   —— 单行、`key=value`、便于 `grep`/日志采集。

2. **越界即 ALERT**（`log.error`，带 `ALERT` 前缀，沿用 P2/P3 风格）：
   ```
   ALERT 点赞积压超阈值 pending=1200 阈值=1000
   ALERT 点赞落库延迟超阈值 lagMs=95000 阈值=60000
   ALERT 点赞缓存非 READY 持续 45s state=BUILDING
   ```

**新增配置项**（全部带默认值，沿用 `tj.like.*`）：

| 配置 | 默认 | 含义 |
|---|---|---|
| `tj.like.metrics.log-interval-ms` | `30000` | 周期 INFO 间隔 |
| `tj.like.alert.pending-threshold` | `1000` | pending 告警阈值 |
| `tj.like.alert.lag-ms-threshold` | `60000` | 延迟告警阈值 |
| `tj.like.alert.ready-stale-ms` | `30000` | 非 READY 持续多久告警 |

**验收**：
- 人为停掉消费者（或在 `READY=BUILDING` 时）→ **N 秒内**日志出现对应 `ALERT` 行；
- 正常运行时每 30 秒恰好一条 INFO（不刷屏）；一次积压恢复后 ALERT 停止；
- **告警不得导致任何写操作**（只读 Redis 状态）。

---

### B2 对账（只报告，不自动修复）

**要回答的问题**：Redis 的关系/计数与 MySQL 到底一致吗？

**比对项**（低频执行，默认每 5 分钟，配置 `tj.like.reconcile.interval-ms:300000`）：

1. **关系集合**：`SMEMBERS remark:like:user:<bizType>:<userId>` ↔ `SELECT biz_id FROM liked_record WHERE user_id=? AND biz_type=?`
2. **计数**：`ZRANGE remark:like:count:<bizType> WITHSCORES` 的 score ↔ 该 bizId 在 `liked_record` 的行数

**关键约束**：

- **抽样而非全量**：全量扫描在生产上不可接受。默认抽最近活跃的 N 个用户（N 可配，默认 50）或最近 N 分钟有变更的 bizId；
- **必须排除“正常中间态”**：Stream 里还有未落库记录时（`XLEN>0`），Redis 领先 MySQL 是**预期行为**，此时**不得报差异**；
- **只报告**：发现差异 → `log.error("ALERT 点赞对账差异 ...")` 打印**具体 bizId 与两侧值**，不做任何修复；
- **与 B1 的 ALERT 区分前缀**（例如 `ALERT[reconcile]`），便于运维分辨。

**验收**：
- 人为造一条“只在 Redis、MySQL 里没有”的假成员（`SADD ... 888888` 且确认 `SELECT` 为 0 行）→ **下一次对账必须报 ALERT**，并且**不得**自动删除该成员；
- 正常运行时（`XLEN=0`、数据一致）→ 对账**静默**，不产生 ALERT；
- 有未落库积压时（`XLEN>0`）→ 对账**不得**误报。

---

### B3 降级语义：读取不得以空值伪装故障（审计 + 补齐）

**要回答的问题**：Redis 挂了，客户端看到的是明确的失败，还是“0 个赞 / 未点赞”？

**动作**：审计 `tj-remark` 全部读取路径，逐一确认**不存在**“捕获 Redis 异常 → 返回 0/false/空集合”的写法。

已知 P3-2 已整改 `LikeCacheService` 的 4 处（`:54` / `:85` / `:116` / `:142`，改为捕获 `DataAccessException` 并转 `LikeServiceUnavailableException` → 503）。本批要覆盖**其余读取路径**：

- `LikedRecordController` 的 `/likes/status`、`/likes/times`、`/likes/list`（若有）；
- 任何通过 `redisTemplate` 直接读取、且带 `try/catch` 的方法；
- `LikeCacheInitializer` 读取路径（初始化期失败应保持 BUILDING + ALERT，而不是置 READY）。

**判据**：Redis 命令超时 → 对外 **503**（不是 200 + 0）；编程错误（如类型不符）**不得**被翻译成 503。

**验收**：测试用**真实的** `LettuceExceptionConverter`（不要 mock 一个假的超时），断言超时 → 503、编程错误 → 原样抛出。真实环境演练**不做**（会波及其余 11 个服务，风险大于收益），在交付文档里注明。

---

### B4 积压水位拒写

**要回答的问题**：积压到多少就该停止接受新写入？依据是什么？

#### 第一步：实测容量关系（必须给出真实数字）

在**服务器 Redis**（6.2.6，`maxmemory` 未设置，宿主可用内存紧张）上：

1. 记录灌入前 `INFO memory` 的 `used_memory`；
2. 用 `XADD` 灌入 N 条（与真实消息结构一致的 6 字段），测出**单条消息的平均字节数**；
3. 重置后灌入更多，验证线性关系；
4. 结合**宿主剩余内存**（`free -m`）与 `maxmemory` 是否要一并设置，**推导出水位数字**；
5. 把原始测量数据（N、used_memory 增量、单条字节数、计算过程）写进交付文档。

> 注意：宿主上还跑着 10 个容器，Redis 只是其中之一。水位必须留足余量，**宁可提前拒写，也不能把宿主拖进 OOM**。

#### 第二步：实现拒写

- 判定依据：Stream 长度（`XLEN`）超过水位即拒绝新写入；
- **恢复阈值**：低于水位的某个比例（如 **80%**）才恢复接受，避免在阈值附近抖动；
- **对外表现**：仍然是 **503**（与未 READY 一致），客户端不需要区分两种 503 的业务码含义，但日志要能区分；
- **已接受的消息不丢**：拒写只影响**新请求**，Stream 里已有的必须继续落库。

#### 关于返回码的方案选择（自行决策，但必须说明理由）

- **方案 A（推荐）**：不动 Lua 的 `1/0/-1` 约定，在 `LikeCacheService` 调 Lua **之前**用内存标志做预检（标志由消费任务每轮根据 `XLEN` 更新）。代价是**非原子**：水位边界处可能多写几十条，但有 20% 缓冲，可接受。
- **方案 B**：Lua 新增返回码（如 `-2`）表示“积压拒写”。原子且精确，但**改动了第一批已固定的返回码约定**，需在文档里登记并同步调用方。

**验收**：
- 人为灌到超过水位 → 新写请求得到 503，且 **Stream 里已有记录继续被落库**；
- 降到恢复阈值以下 → 自动恢复接受写入（无需重启）；
- 拒写期间的 503 **不产生** 新的 Stream 记录（即真的没写进去）。

---

### B5 第一批验收挖出的尾巴（一并收掉）

| # | 问题 | 位置 | 要求 |
|---|---|---|---|
| B5-1 | `deadLetterBackoffs` 只增不减，长期运行内存无上限 | `LikeWriteBehindTask:57`（仅 `:345` 附近 `remove`） | 坏记录成功进死信后**及时移除**；再加一个条目数上限兜底（超限时清掉最旧的），并说明选择该策略的理由 |
| B5-2 | `retrySnapshots` 是内存 Map，重启丢 | `LikeCacheInitializer:51` | **本批不改**：这是“重建期间 MQ 补发失败”的兜底，重启后可由 B2 对账发现差异。必须在文档里写明这条**已知边界**，不许悄悄留着当成没看见 |
| B5-3 | “已 ACK 未 XDEL”的残留会占 `XLEN`，且无清理手段 | `LikeWriteBehindTask:133-137`（P3-1 方案 b 的副作用） | **只加告警**：`XLEN>0 且 pending==0` 持续超过 N 分钟 → `ALERT`，文档给出人工 `XTRIM MINID` 的判据与前提（本 Stream 只有一个 group）。**绝不自动 trim** |
| B5-4 | 兜底队列 `remark.note.liked.times.queue` 在 NOTE 上线前的过渡期会堆积 | `LikeRabbitConfig:23` | 纳入 B1 指标（消息数），超阈值 ALERT。**不改代码逻辑** |
| B5-5 | `qa.liked.times.queue` 有 3 条遗留消息 | 服务器 RabbitMQ | 先**查清来源**（是否历史遗留/是否有消费者）；结论写进文档，**不要盲目 purge** |

---

## 3. 交付格式（每一批都要留这三样）

1. **实际改动清单**：文件 + 行号 + 一句话说明（不要贴整文件）；
2. **自动化测试结果**：每项测试的“先失败 / 后通过”两份输出；总测试数（第一批收尾为 27 个，本批之后应增加多少要说清）；
3. **未完成的真实环境验证**：明确列出哪些只能靠真实环境验、当前做到哪一步。

**必须先通过本批验收，才允许进入第三批（生产验收）。**

---

## 4. 本批验收判据（来自第一批规划，逐条对齐）

| 判据（原话） | 对应任务 | 怎么验 |
|---|---|---|
| 人为停掉消费者后**能告警** | B1 | 停消费者 → 等阈值 → 日志出现 `ALERT`；恢复后 ALERT 停止 |
| 待落库消息**不会触发误修** | B2 | `XLEN>0` 时对账静默；造假成员 → 报 ALERT 但不自动删除 |
| Redis 故障或容量保护触发时，客户端得到**明确失败** | B3 / B4 | 超时 → 503；超水位 → 503，且 Stream 里已有记录继续落库 |
| **已接受的消息不丢** | B4 | 拒写期间与之后，之前写入的记录全部落库，`XLEN` 归零 |
| 积压水位**有实测依据** | B4 | 交付文档里有原始测量数据与推导过程，不是拍脑袋的数字 |

---

## 5. 明确不做的事（避免范围蔓延）

- 不接 Prometheus / Grafana / 告警通道（第三批再议）；
- 不做自动重建（`READY` 丢失仍由人工重启恢复 —— 只做告警，理由见第一批手册的 🟡 条目）；
- 不改 `LikeCacheService` 的对外 503 语义（第一批已定）；
- 不动 `tj-learning` / `tj-admin` 等无关模块；
- 不顺手统一行尾符、不格式化、不重命名。
