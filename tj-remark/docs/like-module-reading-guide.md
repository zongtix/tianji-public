# 点赞模块 阅读导读

> 给「一周没跟改动、现在看不懂自己项目」的自己看。
> 按依赖顺序排。每个文件三段：**它解决什么问题 → 核心是哪几行 → 面试怎么讲**。
> 标 ⭐ 的是必看（面试会问），标 ➕ 的是本期新增，标 🔵 的知道职责即可。

---

## 0. 一句话说清这个模块

**点赞是高频写、低频读。** 每次点赞都去更新 MySQL，数据库扛不住；只写 Redis 又怕丢。
所以：**Redis 做权威 + 立即返回 200，后台线程批量把变更落 MySQL，再发 MQ 通知下游。**

这就是 **Write-Behind（写缓冲）** 模式。你课程里原来的点赞是「直接写库」，本期改成了这个。

---

## 1. 全局数据流（记住这张图，其他都是细节）

```
【写路径 —— 同步，必须快】
  POST /likes
    └─ LikeCacheService.change()
         ├─ ① 先问监控：积压/状态过期？→ 是则抛 503
         └─ ② 执行 Lua（原子，单次往返）
              SADD/SREM  user:{bizType}:{userId}   ← 关系（谁赞了谁）
              ZINCRBY    count:{bizType}           ← 计数（排行榜）
              HSET       version:{bizType}         ← 版本（微秒时间戳）
              XADD       change Stream             ← 变更日志
    ← 返回 200，不等 MySQL

【落库路径 —— 异步，每秒一批】
  @Scheduled(1s) LikeWriteBehindTask.flushChangesAndPublish()
    ├─ ③ Redisson tryLock(WORKER_LOCK)   ← 多实例同时跑，但只有一个消费
    ├─ ④ readOneStreamBatch()
    │     先读 PEL 里的 pending（配额 250）
    │     再读新消息（凑满 500）
    ├─ ⑤ LikeChangePersistenceService.persistBatch()
    │     批内按 version 取每条记录的最终态
    │     单条多值 INSERT ... ON DUPLICATE KEY UPDATE id=id
    │     单条多值 DELETE
    ├─ ⑥ LikeTimesPublisher.publish()     ← MQ 发点赞数快照
    └─ ⑦ XACK + XDEL                      ← 只有 MQ 确认了才 ACK

【重建路径 —— 启动时】
  LikeCacheInitializer.run()  (ApplicationRunner)
    ├─ Redisson lock(INITIALIZE_LOCK)     ← 多实例只重建一次
    ├─ READY = BUILDING                   ← 停掉读写
    ├─ drainChanges()                     ← 先把 Stream 排空
    ├─ clearLikeNamespace()               ← 清空 Redis 命名空间
    ├─ loadExistingRecords()              ← 从 MySQL 载回
    ├─ resendFailedSnapshots()            ← 补发排空期间失败的快照
    └─ READY = READY

【两个巡检 —— 只观察，不改数据】
  @Scheduled(5min)  LikeReconcileTask  抽样比对 Redis vs MySQL，差异只 ALERT
  @Scheduled(?)     LikeMetricsTask    积压/pending/延迟 → 告警 + 决定要不要拒写
```

**一个关键概念：`READY` 标记。**
它是整个模块的总开关。不是 `READY` 时，所有读写接口一律 503（`LikeCacheService:143-153`）。
为什么需要它？因为重建时 Redis 是空的，如果这时放请求进来，用户会看到「我的点赞全没了」。**宁可 503，不可给错数据。**

---

## 2. 逐文件导读

### ⭐ `LikeCacheService.java`（175 行｜+7/-?）
**解决什么**：点赞接口的入口。所有请求从这里进 Redis。

**核心三行**（`:39-43`）：
```java
public void change(Long userId, String bizType, Long bizId, Boolean liked) {
    validateBizType(bizType);
    if (metricsTask.rejectingWrites()) {
        throw new LikeServiceUnavailableException("点赞积压或监控状态过期，暂时拒绝新请求");
    }
```
➕ 那个 `rejectingWrites()` 是本期加的 —— **积压保护**：如果 Stream 积压超过水位（默认 50000）或监控状态过期，直接拒绝新写入，防止 Redis 被打爆。

**还有个设计要点**（`:36-39` 注释）：**"按目标状态设置，而不是 toggle"** —— 传 `liked=true/false` 而不是「翻转」。
- 好处：**天然幂等**。同一个请求重试 100 次，结果一样。
- 如果做成 toggle，重试就会把点赞变成取消。

**面试怎么讲**：
> 「点赞接口设计成 set-state 而不是 toggle，是为了幂等 —— 网络重试、MQ 重投都不会算错。这是幂等设计里最基本的一条：状态用『设为 X』而不是『翻转』来表达。」

---

### ⭐ `like-change.lua`（45 行）
**解决什么**：把「改关系 + 改计数 + 记版本 + 写日志」四步合成**一次原子操作**。

**为什么必须用 Lua**：如果分成 4 条 Redis 命令发出去，中间会插进别的请求，导致**关系改了但计数没改**这类不一致。Lua 在 Redis 里单线程执行，整段不会被打断。

**核心逻辑**：
```lua
-- 1. 只在关系真的变了才继续（:14-16）
changed = SADD/SREM(userSet, bizId)
if changed == 0 then return 0 end      -- 重复点赞，直接返回，不计数不写日志

-- 2. 改计数，降到 0 就从排行榜删掉，避免历史 id 永久占空间（:22-27）
count = ZINCRBY(countZSet, ±1, bizId)

-- 3. 版本号 = Redis 服务器微秒时间戳（:30-33）
timeVersion = TIME()秒*1000000 + TIME()微秒
version = max(timeVersion, lastVersion + 1)   -- 同一微秒多次操作时递增
```

**版本号这个设计值得单独讲**：为什么不用自增？
- 因为缓存可能被清空重建，**自增会从 1 重新开始**，跟历史版本冲突。
- 用**服务器微秒时间**做基线，重建后版本依然单调递增。
- `max(timeVersion, lastVersion+1)` 是兜底：万一同一微秒有两次操作，也不会拿到相同版本。

**面试怎么讲**：
> 「用 Lua 保证关系、计数、日志三者的原子性。版本号用 Redis 服务器微秒时间做基线而不是自增，是为了让缓存重建后版本仍然单调递增 —— 这是幂等去重的基础。」

---

### ⭐➕ `LikeWriteBehindTask.java`（434 行｜+103）
**解决什么**：后台消费线程。定时把 Stream 里的变更批量落库 + 发 MQ。**本期改动最大的文件。**

**核心结构**：
```java
@Scheduled(fixedDelayString = "${tj.like.flush-interval-ms:1000}")   // 默认每秒
public void flushChangesAndPublish() {
    if (backoff.shouldSkip(now)) return;                    // 失败退避中，跳过
    if (!"READY".equals(get(READY_KEY))) return;             // 未就绪，不消费
    RLock lock = redissonClient.getLock(WORKER_LOCK);
    if (!lock.tryLock()) return;                            // 别的实例在消费
    try { flushOneStreamBatch(); backoff.onSuccess(); }
    catch (...) { backoff.onFailure(now); }                 // 失败指数退避
    finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
}
```

**四个关键点**：

**① 锁 + 退避**（`:100-122`）
多实例同时跑，Redisson 分布式锁保证**同一时刻只有一个在消费**。失败走指数退避（1s → 60s 封顶），避免故障时打爆下游。

**② 批次组成：pending 配额 + 新消息**（`:269-301`）
```java
pending = read(PENDING_BATCH_QUOTA=250)   // 先读之前失败重试的
fresh   = read(500 - pending.size())      // 再用剩下的配额读新的
```
➕ 为什么留配额给新消息？**防止「始终失败的老 pending」永久堵住后面所有变更。** 这是本期修的坑之一。

**③ ACK 时机：只有 MQ 确认才 ACK**（`:322-359`）
```java
if (allowPublishFailure) {          // 恢复排空阶段
    try { publish(); } catch { 只记内存 failedSnapshots，不抛 }
} else {                            // 正常消费
    try { publish(); } catch { throw; }   // 抛出去 → 不 ACK → 下轮重试
}
if (!recordIds.isEmpty()) { XACK(...); XDEL(...); }
```
**这是整个模块最重要的设计**：**MySQL 事务提交 ≠ 可以 ACK**。
- 落库成功但 MQ 失败 → **不能 ACK**，下轮重发同一版本。
- 下游 `learning` 收到的是 `likedTimes` 快照，**重复收到相同 version 会被它按 `liked_version <` 条件忽略**，所以重发是安全的。

**④ 坏记录隔离 → 死信表**（`:315-319`、`:400-427`）
解析失败（字段缺失、类型错、bizType 非法）的记录**写进死信表后 ACK 掉**，不让它永久堵住 Stream。死信写入失败则**不 ACK、不删**，一直重试。

**面试怎么讲**：
> 「核心是 ACK 时机。消息中间件的『至少一次』语义必须配合消费者端的幂等，我们把 ACK 放在 MySQL 提交 + MQ 确认之后 —— 中间任何一步失败都会重做。重做靠 Stream 的 PEL（未确认消息列表）自动兜住，不需要额外补偿逻辑。」

---

### ⭐ `LikeChangePersistenceService.java`（105 行｜+55）
**解决什么**：把一批变更高效写进 MySQL。**本期改动第二大。**

**两个设计**：

**① 批内按 version 去重**（`:37-53`）
```java
// 如果同一用户在一个批次内多次切换同一对象，只把最后状态写入 MySQL。
for (LikeChange change : changes) {
    if (previousRecord == null || previousRecord.getVersion() < change.getVersion()) {
        latestPerRecord.put(recordKey, change);
    }
}
```
**⚠️ 注意：这个版本比较只在「这一批内」有效**（详见 §4 已知限制）。

**② 单条多值 SQL 替代逐条 execute**（`:67-82`）
```java
INSERT INTO liked_record (user_id, biz_id, biz_type) VALUES (?,?,?),(?,?,?),(?,?,?)
    ON DUPLICATE KEY UPDATE id = id
DELETE FROM liked_record WHERE (user_id, biz_id, biz_type) IN ((...),(...),(...))
```
➕ **这是修「Stream 停摆」的关键改动**。
原来的写法是 `batchUpdate` 逐条执行 —— `jstack` 抓到消费线程卡在 MySQL 驱动的 `executeBatchSerially` 里持锁 70 多秒，整个消费停摆。改成单条多值 SQL 后，**一次网络往返写完 500 条**。

`ON DUPLICATE KEY UPDATE id = id` 的意思是「已经有了就什么都不做」，保证**重复执行不会报错**（幂等）。

**另外加了 `@Transactional(timeout = 20)`**（`:31`）—— 给锁持有时间一个硬上界。即使 SQL 卡住，20 秒后事务回滚释放锁，不会像之前那样无限期停摆。

**面试怎么讲**：
> 「批量落库踩过一个坑：用 `batchUpdate` 时 MySQL 驱动会逐条串行执行，500 条能占住消费线程几十秒，而消费线程持有分布式锁，导致整个消费停摆。改成单条多值 INSERT 后一次往返写完，再加事务超时给锁持有时间封顶。**定位靠 jstack 抓线程栈**，看到卡在 `executeBatchSerially` 才找到根因。」

---

### ⭐ `LikeTimesPublisher.java`（69 行｜+8）
**解决什么**：把点赞数快照发给下游 `learning` 服务。

**核心：先发一批，再统一等确认**（`:45-67`）
```java
for (...) { rabbitTemplate.convertAndSend(..., correlationData); }   // 先全发出去
for (CorrelationData cd : pendingConfirms) {                          // 再逐个等
    confirm = cd.getFuture().get(5, SECONDS);
    if (!confirm.isAck()) throw new LikePublishRetryableException(...);
    if (cd.getReturned() != null) throw new LikePublishUnroutableException(...);
}
```
- **先发后等**，而不是发一条等一条 —— 避免每条消息一次 Broker 往返。
- **两种失败分开处理**：
  - `Retryable`（Broker 没确认）→ 退避重试
  - `Unroutable`（routing key 没有匹配队列）→ **管线暂停**，因为这是配置问题，重试再多次也没用（`LikeWriteBehindTask:108-110`）
- ➕ `messageId` 里带了 `version`，下游可据此幂等。

**面试怎么讲**：
> 「消息发布用 publisher-confirm 模式，把『发出去』和『Broker 确认收到了』分开。失败分两类：一类是网络/压力问题可以重试，另一类是 routing key 配错了 —— 后者的正确反应是停下来告警，而不是疯狂重试。」

---

### ⭐ `LikeCacheInitializer.java`（284 行｜+23）
**解决什么**：启动时把 MySQL 的点赞记录装进 Redis。

**核心流程**（`:58-92`）：
```java
RLock lock = redissonClient.getLock(INITIALIZE_LOCK);
lock.lock();                                    // 多实例只重建一次
if ("READY".equals(state)) return;              // 已经是 READY，不动
redisTemplate.set(READY_KEY, "BUILDING");       // ① 停掉所有读写
RLock workerLock = lock(WORKER_LOCK);           // ② 同时挡住后台消费
try {
    createChangeStreamGroup();
    drainChanges(failedSnapshots);              // ③ 先把 Stream 排空（重要！）
    clearLikeNamespace();                       // ④ 清空 Redis
    loadExistingRecords();                      // ⑤ 从 MySQL 载回
    resendFailedSnapshots(failedSnapshots);     // ⑥ 补发排空期间的失败快照
    set(READY_KEY, "READY");                    // ⑦ 放行
} finally { workerLock.unlock(); }
```

**为什么顺序不能变**：
- **③ 必须在 ④ 之前**：Stream 里可能有没落库的变更，先落库再清空。否则清空 Redis 时那些变更就丢了。
- **④ 必须在 ⑤ 之前**：`clearLikeNamespace()` 清掉旧的（可能是残缺的）数据，`loadExistingRecords()` 从 MySQL 权威源重建。
- **⑥ 在 ⑤ 之后**：排空时 MQ 如果失败，快照存在内存里，等 Redis 好了再补发。

**面试怎么讲**：
> 「缓存重建的顺序是关键：先排空未落库的变更、再清空缓存、再从数据库载回。反过来的话，排空前清空会把未落库的变更丢弃。整个重建期间 READY 置为 BUILDING，所有读写返回 503 —— 宁可拒绝服务，不可返回错误数据。」

---

### ➕ `LikeMetricsTask.java`（287 行｜新增）
**解决什么**：巡检 + 告警 + 决定要不要拒写。

**职责**（不必逐行看）：
- 采样 Stream 长度、PEL 数量、落库延迟、失败计数
- 超阈值 → `log.error("ALERT ...")`
- `rejectingWrites()`：**积压超水位（50000）或监控状态过期** → 供 `LikeCacheService` 判断 → 503
- `pendingLag` 超阈值（60000ms）→ 延迟告警，**每 60 秒重报一次**

**面试怎么讲**：
> 「监控值不值得为它写代码？值得 —— 我们压测时 30.5% 的请求被误拒，就是因为监控状态过期判定太紧（2 秒），在请求线程里触发了拒写。**可观测性故障不应该导致业务拒写。** 后来改成过期时实时查一次 Stream，只有真读不到才拒写。」

---

### ➕ `LikeReconcileTask.java`（146 行｜新增）
**解决什么**：定期抽样比对 Redis 和 MySQL，发现不一致就告警。

**核心**（`:66-124`）：
```java
List<LikedRecord> recent = mapper.selectList(...orderByDesc("id").last("LIMIT " + limit*4));
// 抽 50 个用户，两边各取集合，比对差集
Set<String> allIds = redisIds ∪ databaseIds;
for (bizId : allIds) {
    if (redisIds.contains(bizId) != databaseIds.contains(bizId)) {
        differences.add("点赞关系差异 ...");
    }
}
```
**三个限制**（重要，见 §4）：
- 只抽样最近 200 行，表大了漏报
- **只 `log.error` 告警，不自动修正**
- 只在 Stream 完全为空时才跑（避免把未落库的中间态误报成差异）

**面试怎么讲**：
> 「对账是抽样做的，只告警不修 —— 因为『自动修』需要判断哪边是权威，而权威判断错了会放大错误。生产上这类任务一般是『发现 → 人工介入』，自动化修复反而危险。」

---

### 🔵 `LikeServiceUnavailableException.java`（22 行｜+15）
**解决什么**：降级用的 503 异常。

```java
public class LikeServiceUnavailableException extends CommonException {
    private static final int STATUS = 503;
    @Override public int getStatus() { return STATUS; }   // 显式覆写，不依赖父类硬编码
}
```
➕ 为什么要显式覆写？因为父类 `CommonException` 硬编码返回 500，**不覆写的话降级会变成 500**，契约就不对了。

---

## 3. 面试问答清单（按被问概率排序）

| 问题 | 一句话答案 |
|---|---|
| **为什么用 Redis + 异步落库？** | 点赞高频写，直连 MySQL 扛不住。Redis 做权威秒回，后台批量落库。 |
| **异步落库不会丢吗？** | Redis Stream 的 PEL 兜底 —— 只有 MySQL 提交 **且** MQ 确认才 ACK，中间失败会重做。 |
| **重复消费怎么办？** | 三重幂等：① 接口是 set-state 不是 toggle；② `INSERT ... ON DUPLICATE KEY UPDATE id=id`；③ 下游按 `version` 条件更新。 |
| **为什么用 Lua？** | 关系、计数、日志必须原子。分开发命令会被其他请求插进来，导致计数和关系不一致。 |
| **version 为什么不用自增？** | 缓存重建后自增会从 1 重来，和历史的冲突。用 Redis 微秒时间做基线，重建后仍单调递增。 |
| **分布式锁用在哪？** | 两个：`WORKER_LOCK` 保证多实例只有一个消费 Stream；`INITIALIZE_LOCK` 保证只重建一次缓存。 |
| **缓存重建为什么要有 READY 标记？** | 重建期间 Redis 是空的，放请求进来用户会看到「点赞全没了」。507/503 比错数据好。 |
| **怎么保证 MQ 消息不丢？** | publisher-confirm + 拿到 ACK 才 ACK Stream。`messageId` 带 version，下游可去重。 |
| **遇到过最难的 bug？** | 消费停摆：`jstack` 抓到线程卡在 MySQL `executeBatchSerially`，且持着分布式锁 + Redisson 看门狗续期，导致监控完全看不见。修法是改单条多值 SQL + 事务超时。 |

---

## 4. 已知限制（不要再修，记录用）

这些是逐行审计找出来的边界情况，需要多条件叠加才触发，**已决定不修**：

| 项 | 触发条件 | 影响 |
|---|---|---|
| **跨批次版本覆盖** | MQ 故障 + 积压 >250 条 + 用户反复点赞/取消 | MySQL 可能出现「用户已取消但仍存一行」。**注意**：重建时会 `loadExistingRecords()` 从 MySQL 载回，所以这个错行会被搬进 Redis 权威 |
| **重建期间补发窗口** | 重建排空 + MQ 再失败 + 进程崩溃 | 下游计数少更新一次，Redis/MySQL 之间仍一致 |
| **100/200 QPS 未实测** | — | 无业务峰值数据，测了也定不出合格线 |
| **NOTE 无消费者** | 有人调用 NOTE 点赞接口 | 消息堆在队列；NOTE 功能接入前需补消费者 |
| **Lua 不回滚** | 人为把 `like:count:*` 键写成非 ZSet 类型 | 关系和计数可能不一致 |

**已通过的部分**（这些是有实测证据的）：
- 三批功能完整
- C1 备份可恢复 / R1-R3 故障注入 / C3 压测 50 QPS 零误拒
- 测试 Java 49/49、Python 8/8
