# 任务：tj-remark 点赞 write-behind 模块 —— 第一批修复

> 执行前先读完「总约束」。本批次**只做 P0 三项**，其余一律不要动。

---

## 总约束（违反任一条即停下问我）

1. **只做 §P0 列出的 3 项。** 不要顺带重构、不要重命名、不要调整格式、不要补注释、不要"顺手优化"相邻代码。
2. **不要写任何新的 markdown / 说明文档。** 改完只输出：改了哪个文件、哪一行、diff 摘要、怎么验证。
3. **禁止改动**（这些是已确认正确的设计，动了会破坏正确性）：
   - `redis/like-change.lua` 的**版本号生成逻辑**（`TIME` 微秒基线 + `max(timeVersion, lastVersion+1)`）
   - `LikeWriteBehindTask.flushOneStreamBatch()` 里 **「落库 → 发 MQ → 再 ACK」的顺序**
   - `liked_record` 的唯一索引 `uk_user_biz_type (user_id, biz_type, biz_id)`（已确认存在于数据库，`ON DUPLICATE KEY UPDATE id = id` 的幂等性依赖它）
   - `LikeTimesPublisher` 的 confirm / return 双重校验逻辑
   - Redis 的 `noeviction` 行为
4. **不要新增依赖、不要改数据库结构、不要改 Nacos 配置、不要改 `bootstrap.yml`。**
5. 每项改动必须是**可独立验证的最小改动**。
6. 改动**逐个提交**（一项一个 commit），不要混成一坨。

---

## P0-1 缓存重建会误删 Stream（数据丢失风险）

**文件**：`tj-remark/src/main/java/com/tianji/remark/task/LikeCacheInitializer.java:109-135`

**现状**：

```java
private void clearLikeNamespace() {
    ...
    ScanOptions options = ScanOptions.scanOptions()
            .match(LikeCacheKeys.PREFIX + "*")     // PREFIX = "remark:like:"
    ...
    // 只跳过了 READY_KEY
    if (Arrays.equals(readyKey, key)) { continue; }
```

**问题**：`LikeCacheKeys.CHANGE_STREAM` = `remark:like:changes`，**也匹配 `remark:like:*`，会被一起删掉**。目前只靠「BUILDING 期间写入口拒绝服务」这一层隐式保护，没有任何显式说明。

**期望**：
1. 清理时**显式跳过** `LikeCacheKeys.CHANGE_STREAM`（与已跳过的 `READY_KEY` 同等对待）。
2. 在跳过 Stream 的地方加**一行注释**说明原因（这是唯一的注释例外）：Stream 里可能还有未落库的变更。

**验收**：
- 构造场景：Stream 里有未 ACK 的消息 + Redis 里 `cache:ready` 处于 `BUILDING`
- 执行初始化后：`XLEN remark:like:changes` 与 `XINFO GROUPS remark:like:changes` 的消费组必须**仍然存在**（当前会被清掉）

---

## P0-2 同一方法内两套「取最新」语义不一致

**文件**：`tj-remark/src/main/java/com/tianji/remark/service/LikeChangePersistenceService.java:46-53`

**现状**：

```java
for (LikeChange change : changes) {
    latestPerRecord.put(recordKey(change), change);              // ← 按【数组到达顺序】覆盖
    String bizKey = bizKey(change.getBizType(), change.getBizId());
    LikeChange previous = latestPerBiz.get(bizKey);
    if (previous == null || previous.getVersion() < change.getVersion()) {
        latestPerBiz.put(bizKey, change);                        // ← 按【version】取最大
    }
}
```

**问题**：同一个循环里，`latestPerRecord` 按到达顺序覆盖，`latestPerBiz` 按版本号比较。今天没暴露问题，是因为 Lua 脚本原子执行使「XADD 顺序 == 版本号顺序」—— 这是**隐式耦合**。一旦有人用 `XCLAIM` 重放消息子集、或人工重投，落库就会写到旧状态。

**期望**：`latestPerRecord` 也按 `version` 取最大，与 `latestPerBiz` 保持同一语义。
- 保持按 `recordKey(change)`（即 `bizType:bizId:userId`）分组不变
- 版本比较用 `long` 比较（version 是 16 位数字字符串，`LikeChange.getVersion()` 已是 Long）

**验收**：
- 用 JUnit 构造入参：同一个 `(userId, bizId, bizType)` 出现 3 次，`version` **乱序**（例如 300, 100, 200）
- `persistBatch` 之后，该记录的最终状态必须是 `version=300` 那一条的 `liked` 值
- 且 `likes` / `unlikes` 的分组结果同样以 version 最新者为准

---

## P0-3 Stream 没有长度上限（内存无刹车）

**文件**：`tj-remark/src/main/resources/redis/like-change.lua:32-38`

**现状**：

```lua
redis.call('XADD', KEYS[4], '*',
    'userId', ARGV[2],
    ...)
```

**问题**：`XADD` 不带 `MAXLEN`。正常流下靠 `LikeWriteBehindTask` 的「ACK 后 DEL」控制体积（已验证有效）。但**消费一旦停摆**（消费者挂了、下游队列缺绑定导致批次永久重试），Stream 会无上限增长直到 Redis 内存打满。当前 `maxmemory=0` + `noeviction`，打满后写入直接失败。

**期望**：给 `XADD` 加**近似**修剪：

```lua
redis.call('XADD', KEYS[4], 'MAXLEN', '~', '10000', '*', ...)
```

- 必须用 `~`（近似模式），**不要**用精确 `MAXLEN 10000`（精确模式每条都真裁剪，性能差）
- 阈值用常量，不要散落魔法数字

**验收**：
- 用 `redis-cli --eval`（测试命名空间，别碰 `remark:like:*` 业务键）连续写入 20000 条，`XLEN` 应稳定在 10000 量级而不是 20000
- 确认 `XINFO STREAM` 的 `max-deleted-entry-id` 有推进

---

## 不要做（已知问题，**故意留到第二批**，等我定方案）

以下问题都已确认存在，但**不属于本批次**。**不要自行设计、不要顺手改、不要提出方案**，本批次完全不碰：

1. **下游队列路由失败会阻塞整条管线** —— `LikeTimesPublisher:48-53` 抛异常 → 不 ACK → 永久重试同一批。需要先定「死信 vs 隔离 vs 阻塞」策略。
2. **没有降级路径** —— `LikeCacheService` 三处 READY 校验失败直接抛异常，Redis 故障时点赞整体不可用。需要先定读/写各自的降级语义。
3. **没有对账机制** —— `remark:like:count:<bizType>` 是计数器，与 DB 漂移后无法发现。
4. **没有监控指标** —— `XPENDING` / 批次耗时 / 失败次数均未暴露。
5. **失败批次无退避、无重试上限** —— 每秒重试，永久失败会刷日志。
6. **键基数问题** —— 每个用户一个 Set key，百万用户量级需重新设计。
7. **`versionHash` 字段永不清除** —— `ZREM` 时未同步 `HDEL`，改为清除需评估时钟回退风险。
8. **`remark.note.liked.times.queue` 无消费者无 TTL** —— 消息永久堆积。

---

## 交付格式

改完按这个格式回我，不要写别的：

```
## P0-1
文件: 路径:行号
diff: <关键改动，3-10 行>
验证: <你实际跑的命令 + 实际输出>

## P0-2
...
```

**不要**输出架构说明、不要输出 mermaid 图、不要输出"改进建议"、不要输出总结散文。
