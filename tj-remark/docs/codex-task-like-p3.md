# 收尾任务规格（P3）—— 两个边界缺陷

> 背景：P2（恢复正确性 + 坏记录治理）已交付并通过独立复跑：
> `Tests run: 19, Failures: 0, Errors: 0` / `BUILD SUCCESS`（JDK 11）。
> 本批**只修两个已定位的缺陷**，都在 `tj-remark` 内，都与 P2 新写的代码有关。

---

## 0. 总约束

沿用 P1 / P2 的约定：只改下面列出的文件、不改对外接口签名、不改表结构、
不新增依赖、**不执行任何 git 命令**、交付必须给实际命令与实际输出。

本批新增一条：

8. **每项必须先写能复现的测试，确认它在改实现之前是失败的（红），改完再通过（绿）。**
   这两个都是边界缺陷，没有测试就等于没改 —— 下一次重构会立刻退回去。

---

## P3-1 排空判据被「已确认但未删除」的残留条目卡死

### 涉及文件

`tj-remark/src/main/java/com/tianji/remark/task/LikeWriteBehindTask.java:125-157`（`isDrainComplete`）

### 现状

```java
// :130-135
Long length = streams.size(LikeCacheKeys.CHANGE_STREAM);
long pendingCount = streams.pending(LikeCacheKeys.CHANGE_STREAM,
        LikeCacheKeys.CHANGE_GROUP).getTotalPendingMessages();
if (length == null || length != pendingCount) {
    return false;
}
```

### 为什么是错的

`acknowledgeMissingPending()`（`:206-225`）只清理**一半**的不一致：
「PEL 里有、Stream 里没有」的幽灵条目。反过来的一半没有被处理——
**「Stream 里有、PEL 里没有」的条目**，即已 `XACK` 但 `XDEL` 失败的残留。

残留是真实存在的：`:289-294` 的 ACK 与 DEL 是两次独立调用，进程若在两者之间
退出，条目就永久停留在「已确认」状态。此后：

- `XLEN = 1`、汇总 `pending = 0` → `length != pendingCount` **恒成立**
- `isDrainComplete()` 永远返回 `false`
- `LikeCacheInitializer:87-99` 跑满 `MAX_DRAIN_ROUNDS = 10000` 后抛 `IllegalStateException`
- 该异常从 `ApplicationRunner` 抛出 → **Spring Boot 启动失败**
- 容器重启 → 再次对同一个残留执行排空 → **crash loop，服务永远起不来**

排空判据的职责是「还有没有**待处理**的记录」，
而「已确认的残留」不属于待处理——它不该阻塞判据。

### 期望改动

**必须**做到：存在「已 ACK 但未 XDEL」的残留条目时，`isDrainComplete()` 仍能返回 `true`。

推荐做法（二选一，交付说明里写清你选了哪种、为什么）：

- **方案 a（推荐）**：清理残留。
  条件是 `id <= last-delivered-id` **且** id 不在本 group 的 PEL 中。
  **安全性论证（必须写进注释）**：`XREADGROUP` 投递消息时会把 id 加入 PEL，
  所以「已被投递过（id ≤ last-delivered-id）却不在 PEL」只可能是被 `XACK` 了，
  其内容已经落库，删除不会丢数据。
  注意两点：
  1. `XDEL` 是全局的，会同时删掉其他消费组尚未读取的消息。
     **本 Stream 目前只有本服务声明的一个 group**，把这个前提写进注释；
     如果将来出现第二个 group，这个方案必须重新评估。
  2. 不要为了清理而全量扫描 Stream。先取 `last-delivered-id`，
     只对「差额部分」做分页处理，避免积压很大时把排空拖慢。

- **方案 b**：保留条目，只让判据忽略这类残留（即去掉 `length != pendingCount` 这一条，
  改为依赖「新消息读空 + PEL 为空或全是已标记的坏记录」）。
  选这条就必须同时说明：残留会持续占用 Redis 内存，何时清理、由谁清理。

### 验收（必须先有能复现的测试）

1. **复现测试**：mock 出 `XLEN = 1`、汇总 `pending = 0`、
   `last-delivered-id = "1-0"`、`read(...)` 返回空。
   改实现前 `isDrainComplete()` 返回 `false`（红），改完后返回 `true`（绿）。
2. **回归测试**：`XLEN > pendingCount` 但**存在未投递的新消息**（`>` 读到非空）时，
   仍然必须返回 `false` —— 别把「真的还有活干」也放过去了。
3. **幽灵路径不能破**：`deletedPendingEntryIsAcknowledgedWithoutDeletingAnotherRecord`
   和 `recoveryMayFinishWithOnlyMarkedBadRecordPending` 必须继续通过。

---

## P3-2 Redis 异常只捕获了一种，命令超时会漏成 500

### 涉及文件

`tj-remark/src/main/java/com/tianji/remark/service/LikeCacheService.java:54-56`、`:85-87`、`:116-118`

### 现状（三处同一写法）

```java
try {
    changed = redisTemplate.execute(CHANGE_SCRIPT, keys, ...);
} catch (RedisConnectionFailureException e) {
    throw new LikeServiceUnavailableException("点赞服务暂不可用，请稍后重试", e);
}
```

### 为什么是错的

Spring Data Redis 的异常层次里，`RedisConnectionFailureException` 和
`RedisCommandTimeoutException`（Lettuce 命令超时）**都是** `DataAccessException`
的子类，但**互不继承**。所以后者会漏过这个 catch，
落到 `CommonExceptionAdvice:79-83` 的兜底分支
→ 对外 **500「服务器内部异常」**。

这违反了 P2 已确定的对外约定（「Redis 不可用 → 503」），
而且 500 会让调用方以为是自己请求的问题，掩盖了「依赖不可用」这个事实。

### 期望改动

1. 三处 catch 的边界统一放宽到 `org.springframework.dao.DataAccessException`。
2. **不要**用 `catch (Exception e)` 兜底成 503：
   那会把 NPE、脚本返回值类型错误这类**编程错误**也伪装成「服务暂不可用」，
   把 bug 埋进 503 里。`DataAccessException` 是这里正确的边界。
3. 保留原始异常作为 cause（`new LikeServiceUnavailableException(msg, e)`）。
4. `changed == null`（`:57-59`）与 `changed == -1L`（`:60-62`）的分支保持不变。

### 验收（必须先有测试）

对**每一处**（`change` / `queryMyLikedBizIds` / `queryLikedTimes`）：

1. mock 让 Redis 调用抛 `RedisCommandTimeoutException`
   → 断言抛出 `LikeServiceUnavailableException`（改实现前是红的）。
2. mock 抛 `RedisConnectionFailureException` → 仍然抛 `LikeServiceUnavailableException`（回归）。
3. 构造 `RedisCommandTimeoutException` 时注意它是否有无参构造，
   没有就用带 message / cause 的构造器 —— **先写最小验证代码跑一遍再定，不要照着记忆写**。

---

## 不要做

- ❌ 不要改动 P2 已交付的逻辑：Lua 返回码 `1/0/-1`、死信失败不 ACK / 不 XDEL、
  `readOneStreamBatch` 的 pending/fresh 配额分配、D1 排空降级、
  `freshSnapshot` 从重建后状态取值、`version:*` 保留策略
- ❌ 不要改死信表结构、不要改 Redis 键名
- ❌ 不要动 `LikeCacheInitializer` 的流程顺序（S1–S8）
- ❌ 不要顺手加监控埋点 / 对账 / 压测（属于第二、三批）
- ❌ 不要执行 git 命令

---

## 交付格式

每项给四段（沿用 P2）：

1. **改了什么** —— 文件路径 + 行号 + 改动后的代码片段
2. **执行了什么命令** —— 原样贴
3. **实际输出是什么** —— 原样贴（`Tests run:` 行）
4. **对应用例** —— 填上面的编号；红→绿的证据要贴出来（先失败的那次输出也要给）

最后附一段：**你发现但按规格没动的问题。**
