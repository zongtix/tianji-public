# 点赞第三批：独立核验报告

- **核验对象**：`docs/codex-task-like-batch3.md` 的交付（`docs/like-batch3-delivery.md` + `scripts/`）
- **核验方式**：在**真实 ECS** 上执行；核验方持服务器访问权，交付方无
- **核验日期**：2026-09-28
- **结论**：**不能上线**。6 项任务中 C1/R2/R3 通过，R1 结论与规格判据不符，C3 不达标并暴露 3 个严重缺陷。

---

## 0. 核验方法与边界

- **隔离原则**（规格 §1）：不停容器、不改全局配置、不动其他 9 个同宿服务。
  - Redis/MQ 故障用**重启 remark + 端口覆盖**；MySQL 故障用 `KILL` 且只杀 `db='tj_remark'` 的连接。
  - **实测结果**：全程未波及同宿其他服务（10 个容器完好，其他库连接未动）。
- **⚠️ 规格 §1 给出的注入参数不生效**（详见 §3.4），核验改用经实测验证的参数。
- 异步链路判据一律**轮询至收敛**，不用"打一条命令 + sleep + 查一次"。
- 测试数据：`user_id=9998`，各场景独立 `bizId` 段；**全部清理完毕**（清理条数与 HTTP 200 计数完全吻合）。

---

## 1. 逐项结论

| 任务 | 结论 | 关键证据 |
|---|---|---|
| **C1** 备份与恢复 | ✅ **通过** | 备份 + SHA256 校验 + 隔离临时容器恢复，行数与源一致 |
| **R1** Redis 不可用 | ⚠️ **结论与规格不符** | 不是"写接口 503"，而是**应用启动失败**（HTTP 000） |
| **R2** MySQL 不可用 | ✅ **通过** | 落库失败但消息不丢，恢复后自动补落，无丢失 |
| **R3** MQ 不可用 | ✅ **通过** | 写接口仍 200、消息不 ACK、`failCount` 正常计数，恢复后收敛 |
| **C3** 峰值压测 | ⚠️ **不达标** | 50 QPS 下 **30.5% 请求被误拒**；暴露 3 个缺陷 |
| C4/C5/C6 | 文档已核（声明属实） | 见 §4 |

---

## 2. 详细证据

### C1 备份与恢复 ✅

```
BACKUP_OK path=/var/backups/tj-remark/remark-20260928T104747Z
产物: redis-snapshot.rdb(4.2KB) + tj_remark.sql.gz + SHA256SUMS + redis-check.log
      row-counts: liked_record=1, deadletter=0
      binlog before/after 均为 binlog.000002:85811572（备份窗口内无写入）

恢复验证（隔离临时容器）:
  redis-snapshot.rdb: OK          ← SHA256 校验通过
  tj_remark.sql.gz: OK
  REDIS_RESTORE_OK keys=6 stream_xlen=0
  MYSQL_RESTORE_OK liked_record=1 deadletter=0     ← 与源一致
  临时容器残留 0，现有容器 10 个未受影响
```

**"备份存在"已被验证为"备份可恢复"** ✅。同盘风险实测确认：`/var/backups/tj-remark`、`redis/data`、`mysql/data` 同在 `/dev/vda3`，宿主盘故障会同时失去主数据与备份 —— 交付文档已如实写明，不算隐瞒。

### R1 Redis 不可用 ⚠️

**注入**：`-Dspring.redis.port=16379`（死端口，实测无监听）后重启 remark。

**实际现象**：**应用启动失败**，`POST /likes → HTTP 000`。

**根因链**（日志实证）：

```
UnsatisfiedDependencyException: likedRecordController
 → likedRecordServiceImpl → likeCacheService → likeMetricsTask → likeWriteBehindTask
  → BeanCreationException: 'redissonClient' (com/tianji/common/autoconfigure/rediss...)
   → RedisConnectionException: Unable to connect to Redis server: /127.0.0.1:16379
    → ConnectException: Connection refused
```

**结论**：
1. 失败点是 **`redissonClient` Bean 创建**（`tj-common` 的自动配置），**不是** `LikeCacheInitializer`；
2. **启动时不可用**与**运行时断开**行为完全不同：

| Redis 状态 | 行为 | 覆盖 |
|---|---|---|
| 启动时不可用 | 应用起不来（HTTP 000） | R1 |
| 运行时断开 | 接口 503（`DataAccessException`） | 第二批已验证 |

3. 这是 **`tj-common` 的公共行为** —— 所有用 Redisson 的服务都有同样的启动依赖。运维含义：**Redis 必须先恢复，服务才能启动**。

**规格需修正**：R1 判据"写接口 503 / 脚本若得到 HTTP 0 判失败"→ 应改为"应用启动失败即为符合预期"。

### R2 MySQL 不可用 ✅

**注入**：45 秒内循环 `KILL` 仅 `db='tj_remark'` 的连接（实测恰 10 个，Hikari 池），共 22 次 `KILL_ATTEMPT`。

**实测**：

```
注入期: XLEN=3, MySQL 测试段 0 行        ← 消息留在 Stream，没丢
日志  : CJCommunicationsException: Communications link failure
        EOFException: Can not read response from server
轮询  : 第3次 MySQL 测试段 0→3，第4次 XLEN=0  ✔ 收敛
恢复  : 点赞变更批量落库完成，关系变更3条
判据  : HTTP 200 的 3 个 bizId == MySQL 测试段 3 行   ← 无丢失
```

**无丢失** ✅。附带实测：**Hikari 拿不到连接会阻塞 30 秒才超时**（`timeout after 30011ms`），这解释了故障期间积压为何能长期存在。

### R3 MQ 不可用 ✅

**注入**：`-Dtj.mq.port=15673`（经 §3.4 修正的参数）后重启 remark。

**实测**：

```
Attempting to connect to: [127.0.0.1:15673]     ← 注入生效
点赞 3 条 → 全 200                              ← Redis 侧正常
XLEN=3, pending=3, 持续 125+ 秒不变              ← 发布失败就永不 ACK
指标序列:  failCount 0→2→5→6→7   lastSuccessAt=-   noteQueue=-1
          lagMs 2185→32577→64211→95268→125944
          ↑ 越过阈值 60000 时已正常触发 1 次「ALERT 点赞落库延迟超阈值」（18:59:41, lagMs=60256）
ALERT: 点赞监控读取 NOTE 队列失败
恢复: XLEN=0（第1次采样收敛）, PENDING=0
       MySQL bizId = 990200000,990200001,990200002  ← 与 HTTP 200 一致
       lastSuccessAt=- → 19:02:13（发布恢复）
```

**无丢失** ✅。**关键对照**：MQ 失败走 `AmqpException`，`failCount` **正常增长**；而 R2 的 DB 失败 `failCount` 恒为 0 —— 坐实了 §3.3 的盲区。

### C3 峰值压测 ⚠️ 不达标

50 QPS × 30 秒（1500 条）：

| 指标 | 实测 |
|---|---|
| 状态分布 | **200: 1043 / 503: 457（30.5%）** |
| P50 / P95 / P99 / max | 152ms / 229ms / 652ms / 813ms |
| 实际 TPS | **33.7**（目标 50） |
| XLEN 峰值 | 713（**远低于水位 50000**） |
| Redis `used_memory` | 5.5 → 5.7MB（**几乎不涨**） |
| 宿主 `available` | 稳定 1796-1815MB（**安全线未触发**） |
| 落库耗时 | 1500 条排空约 **3 分钟**，期间 `XLEN` 长时间不动 |

**结论**：瓶颈**不在 Redis 内存、不在 B4 水位**，而在**消费侧落库慢 + 锁机制 + 采样过期误拒**。未继续加档（100/200 QPS）—— 50 QPS 已暴露阻塞性缺陷，加档只会放大，无新信息量。

---

## 3. 缺陷清单

### 🔴 3.1 锁的临界区包含慢操作，且看门狗持续续期 → 消费停摆

**位置**：`tj-remark/src/main/java/com/tianji/remark/task/LikeWriteBehindTask.java:98-104`

```java
RLock lock = redissonClient.getLock(LikeCacheKeys.WORKER_LOCK);
if (!lock.tryLock()) { return; }      // 非阻塞，拿不到就空转
try {
    flushOneStreamBatch();             // ← 临界区含 MySQL 批量落库 + MQ 发布
    backoff.onSuccess();
} finally { lock.unlock(); }
```

**现象**（jstack + Redis 实证）：

```
锁 key : lock:remark:like:worker   type=hash  TTL=27（看门狗续期，不会过期）
持有者 : 5723bab9-...:122   ← 线程 ID 122
线程   : "scheduling-4" #122  RUNNABLE
         SocketInputStream.socketRead0
         → com.mysql.cj.protocol...readMessage
         → ClientPreparedStatement.executeBatchSerially   ← 批量 INSERT 落库
其他   : scheduling-1/2/3 全部 TIMED_WAITING（空闲）
消费者 : remark-like-writer  idle=72s, pending=489
指标   : failCount=0, 无 ERROR/WARN, ready=READY   ← 从指标看完全"健康"
```

**影响**：
- DB/MQ 一慢，锁即被长期持有（看门狗续期，**永不自动过期**），同实例其他线程 `tryLock` 全部失败 → **消费完全停摆**；
- 且**指标不可见**（`failCount=0`、无异常、`READY` 正常）→ 监控无法发现；
- 该线程若**永久卡住**（如 DB 无响应），**消费永久停摆，只能重启进程**。

**建议**（任选其一，按改动量排序）：
1. **锁内只做"选取一批"，把落库+发布移出临界区**（最小改动，但需保证单实例消费的语义不破）；
2. 给 `tryLock` 显式 `leaseTime`（如 30s），使持有者异常时锁能自动释放（改动最小，但需评估与看门狗续期的取舍）；
3. 临界区内加**操作超时**（DB/MQ 都有超时上界），确保锁持有时间有硬上界。

### 🔴 3.2 `sampleStaleMs` 过紧，负载下大面积误拒

**位置**：`tj-remark/src/main/java/com/tianji/remark/task/LikeMetricsTask.java:74-82`（`rejectingWrites()`），`sample()` 为 `@Scheduled(fixedDelayString="${tj.like.metrics.poll-ms:1000}")`

```java
public boolean rejectingWrites() {
    if (lastSampleAt == 0 || System.currentTimeMillis() - lastSampleAt > sampleStaleMs) {
        if (staleSampleAlerted.compareAndSet(false, true)) {
            log.error("ALERT 点赞积压监控状态过期，拒绝新写入");
        }
        return true;      // ← 采样一过期，所有写请求直接 503
    }
    return backlogRejecting;
}
```

**证据**：`ALERT 点赞积压监控状态过期` 出现在 **`[nio-8091-exec-7]`**（**请求线程**），说明是请求路径判定采样过期。

**机制**：采样任务 `fixedDelay=1000ms`（**上一次结束后**再等 1 秒），而 `XLEN>0` 时 `sampleAt` 还要额外执行 `groups()` + `range()` 等 Redis 往返；负载下间隔一旦超过 `sampleStaleMs=2000`，`rejectingWrites()` 即对所有写请求返回 true。

**影响**：**把"可观测性抖动"升级成"业务大面积失败"** —— 实测 30.5% 的 503。

**建议**：
1. 调大 `sampleStaleMs`（需以实测采样间隔 P99 为依据，而非拍脑袋）；
2. 或把"采样过期"降级为**只告警不拒写**（积压拒写仍由 `backlogRejecting` 负责）—— 语义更合理：**可观测性故障不应导致业务拒写**；
3. 采样任务与写路径解耦（采样失败不应改变写行为）。

### 🔴 3.3 `failCount` 盲区：DB 连接不可用不被计数

**位置**：`LikeWriteBehindTask.java:314`（`catch (DataAccessException e)` → `failureCount.incrementAndGet()`）与 `:108`（上层同型 catch）

**证据**：R2 注入期落库确实失败（`XLEN=3`、MySQL 0 行、`CJCommunicationsException`），但 `failCount` **全程为 0**；异常首行为：

```
org.springframework.transaction.CannotCreateTransactionException:
    Could not open JDBC Connection for transaction;
    nested exception is java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available
```

`CannotCreateTransactionException` 继承 **`TransactionException`**，**不是** `org.springframework.dao.DataAccessException`，因此两处 catch 都不匹配：
- `failCount` 不累加 → **DB 故障监控不可见**；
- `backoff.onFailure()` 不执行 → **退避策略失效**；
- 异常逃到 Spring 调度层，靠 `TaskUtils$LoggingErrorHandler` 兜底。

**建议**：catch 类型扩为 `catch (DataAccessException | TransactionException e)`（或直接捕获 `CannotCreateTransactionException`），并补一条单测锁定该异常类型会被计数。

### 🟡 3.4 规格给出的故障注入参数不生效

规格 §1 写"用 `-Dspring.rabbitmq.port=15673` 覆盖"，**实测无效**：日志显示仍 `Attempting to connect to: [127.0.0.1:5672]`，netstat 确认连的是 5672。

**根因**：Nacos **默认 namespace** 的 `shared-mq.yaml` 显式定义了 `spring.rabbitmq.port: ${tj.mq.port:5672}`；项目启用 `spring-cloud-starter-bootstrap`，**bootstrap 阶段的远程配置优先级高于 `-D` 命令行参数**，故同 key 被覆盖。`spring.profiles.active` 因读取更早才没被盖住。

**正确参数**（已实测生效）：

| 演练 | Nacos 是否有同 key | 有效参数 |
|---|---|---|
| R3 (MQ) | ✅ 有 `spring.rabbitmq.port` | `-Dtj.mq.port=15673`（改它引用的变量） |
| R1 (Redis) | ❌ `shared-redis.yaml` 无 `port` | `-Dspring.redis.port=16379`（直接生效） |

**建议**：规格改为上表的参数，并加一句"改 `-D` 前先确认 Nacos 是否定义了同 key"。

### ✅ 3.5【更正】`lagMs` 告警实际正常 —— 原判断有误

**本节原先记录"两个进程告警行为不一致、31008 一次没报"。复核后确认该结论错误，予以更正。**

不做任何转码、直接 grep 原文件实测（三个进程各报 1 次，行为完全一致）：

| 进程 | lagMs | 落库延迟告警 |
|---|---|---|
| 31008 | 60256 | ✅ 报 1 次（18:59:41） |
| 16256 | 207932 | ✅ 报 1 次 |
| 24308 | 60948 | ✅ 报 1 次 |

三者均按"越界报一次、恢复后复位"的设计正常触发。**告警功能没有缺陷，两个进程行为一致。**

**原判断的错误根源**：核验命令链是 `grep … | iconv -f GB18030 -t UTF-8 | grep "落库延迟超阈值"`，而这份日志**实际是 UTF-8**（`file` 报 ASCII，是因为它只采样文件开头，中文在后面的行）。按 GB18030 转码后中文全变乱码，第二段 grep 一条也匹配不到 —— 于是造出"从未告警"的假象。

**教训**：日志编码不要凭习惯假设（本项目服务日志落盘为 UTF-8）；**分组计数与明细输出对不上时，那个矛盾本身就是错误信号** —— 当时 `PID 31008 : 0 条` 与紧邻打印出的那条 ALERT 自相矛盾，没有细看就下了结论。

### 🟡 3.6 交付的 Python 脚本只能在 Windows 操作机运行

`scripts/operator_io.py:29` 直接 `socket.create_connection((host, port))` 连 **`127.0.0.1:6379`**，而隧道在 Windows 侧 —— 在 WSL/Linux 侧执行会 `ConnectionRefusedError`。`fault-drill.py` / `load-drill.py` / `cleanup-test-data.py` 都依赖它，故**只能在 Windows 操作机执行**；且它们还要求"先停掉原 remark、再自己启 JAR"，与"只影响 remark、不动现有环境"的核验方式冲突。

**建议**：给 `operator_io.py` 增加 `--redis-host/--redis-port/--mysql-*` 参数（或读取环境变量），使其可在任意操作机执行；或明确文档化"必须 Windows 操作机 + 独占 8091"。

### 🟡 3.7 C1 已知缺口（Codex 自认，核对成立）

- 在线 `appendonly.aof` **只记录文件信息、未做一致性归档**（用 `redis-cli --rdb` 取 RDB 作为恢复源）→ "AOF + RDB 备份"目标**部分未达成**；
- MySQL 记录的是 dump 前后 `SHOW MASTER STATUS`，是**位点区间**而非精确快照位点 → **不能宣称已具备精确 PITR**。

两项交付文档均已如实写明，**核验确认属实，不算隐瞒**。

---

## 4. 与交付文档声明的核对

| 交付文档声明 | 核验结果 |
|---|---|
| "不能宣布可上线" | ✅ 属实，且 C3 进一步印证 |
| "C1 未在 ECS 执行，留空" | ✅ 核验已补齐并**通过** |
| "R1 预期 503 与启动逻辑冲突" | ✅ **预测正确**，实际为启动失败 |
| "C1 AOF/PITR 有缺口" | ✅ 属实 |
| "R2/R3/C3 未实测" | ✅ 核验已补齐（R2/R3 通过，C3 不达标） |
| "6 项 Python 单测通过 + 41 Java 通过" | ✅ 复跑确认：Java **41/41**、Python **6/6**、`py_compile`/`bash -n` 全过 |
| "断言逻辑覆盖已接受/拒绝 ID、双安全线、水位超量" | ✅ 单测覆盖属实，判定逻辑本身正确 |

---

## 5. 未实测项

| 项 | 原因 | 需要什么条件 |
|---|---|---|
| **R4 断电** | 共享宿主不能断电/重启 Redis 容器/改全局持久化配置 | 隔离环境 + 独立磁盘克隆 |
| **运行时 Redis 断开 → 503** | 无法只影响 remark 地切断其 Redis 连接（`CLIENT KILL` 会波及所有服务） | 单测覆盖；或隔离环境 |
| **多实例/灰度下锁与拒写行为** | 需部署 ≥2 实例 | 灰度环境 |

---

## 6. 结论

**本轮核验初版结论（2026-09-28）：不能上线。**

> **同日更新**：§3.1 / §3.2 / §3.3 三个 🔴 已由执行方修复并提交（`8d6ab65`），核验方在真实 ECS 复测 50 QPS：**503 由 457（30.5%）降至 0**，实际 TPS 33.7→48.2，P99 652ms→180ms，`XLEN` 快速收敛、零 ALERT。三条 🔴 **判定关闭**。
>
> **灾备侧仍未关闭**：无独立于 `/dev/vda3` 的备份位置、无云盘快照、无 MySQL 从库；在线 AOF 一致性归档与精确 PITR 位点仍缺。**灾备缺口关闭前，"不能上线"的结论继续维持。**

**已关闭**（🔴，修复后已复测）：
1. **3.1** 锁临界区含慢操作 → 单条多值 SQL 批量落库 + `@Transactional(timeout=20)` 给锁持有时间设硬上界；
2. **3.2** `sampleStaleMs` 过紧 → 采样过期改为实时核验 Stream 长度，仅真读不到时才拒写；
3. **3.3** `failCount` 盲区 → catch 增加 `TransactionException`。

**建议一并处理**（🟡）：3.4 规格参数修正、3.6 脚本可移植性、3.7 C1 缺口补全。
（**3.5 已撤销** —— 原记录的"告警不一致"是核验方的观察错误，详见 §3.5 更正。）

**达标项**：C1 备份可恢复、R2/R3 已接受消息不丢、故障演练不波及其他服务、C3 50 QPS 零误拒 —— 四项**成立**。

**仍未关闭**：灾备独立存储、AOF 一致性归档、精确 PITR 位点、R4 断电、多实例/灰度场景。
