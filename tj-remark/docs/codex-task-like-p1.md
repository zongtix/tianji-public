# 第二批任务规格（P1）—— 点赞模块可靠性

上一批（P0）已完成并提交在 commit `fa76fae`。本批的三个改动都在
`tj-remark` 模块内，**不新增依赖、不改数据库结构、不改对外接口行为**。

---

## 0. 总约束

1. **只改下面明确列出的文件。** 不要顺手改相邻代码，哪怕你觉得它有问题。
   发现了别的问题就写在交付说明里，不要自己动手。
2. 不改任何对外接口签名、不改表结构、不改 Redis 键结构。
3. 不新增任何 Maven 依赖。
4. **每项都必须自己跑验证，交付时给出实际命令 + 实际输出。**
   不要只说"已完成""已验证"。验收没做成功就如实说没做成，**不要编造输出**。
5. **不要执行任何 git 命令**（add / commit / stash 全部不要），
   git 由人来操作。
6. 注释只解释"为什么这么做"，不要复述代码在做什么。
7. 遇到不确定行为的 API（某个方法不存在、参数不对、版本差异），
   **先写最小验证代码跑一遍再定方案**，不要照着记忆写。

---

## 1. P1-1 删掉 Lua 里的 MAXLEN（这是一个丢数据窗口）

### 涉及文件

`tj-remark/src/main/resources/redis/like-change.lua:3` 和 `:33`

### 现状

```lua
line  3:  local STREAM_MAX_LEN = 10000

line 33:  redis.call('XADD', KEYS[4], 'MAXLEN', '~', STREAM_MAX_LEN, '*',
line 34:      'userId', ARGV[2],
...
line 39:      'version', version)
```

### 为什么这是错的（这段是判断依据，不要跳过）

消费端的处理顺序在 `tj-remark/src/main/java/com/tianji/remark/task/LikeWriteBehindTask.java:98-101`：

```java
streams.acknowledge(CHANGE_STREAM, CHANGE_GROUP, recordIds.toArray(new RecordId[0]));
// 已持久化并确认的 Stream 项不再需要保留，及时删除以控制 Redis 内存增长。
streams.delete(CHANGE_STREAM, recordIds.toArray(new RecordId[0]));
```

也就是**「先 XACK，再 XDEL」** —— 处理完的条目已经被显式删除了。
所以正常运行时 Stream 根本不会增长，**不需要 MAXLEN 来兜底**。

那么 MAXLEN 什么时候才会生效？只有一种情况：**消费停摆**
（MQ 路由失败、数据库故障、消费线程异常）。停摆时 Stream 会涨，
涨过 10000 之后 `MAXLEN ~` 会裁掉**最老的条目** ——
而那些恰恰是**还没落库、还挂在 PEL 里等重试的**条目。

关键点：**Redis 6.2.6 的裁剪不检查 PEL。**
（官方文档：`KEEPREF` / `DELREF` 是 Redis 8.2 才引入的选项；
6.x 的默认行为就是「按 MAXLEN/MINID 策略删除，无论条目是否被
消费组引用」，只在 PEL 里留下悬空引用。）

所以被裁掉的 pending 消息，**内容永久丢失**，而 PEL 里还留着 ID，
消费端读到时拿到的是空条目 —— 表现为一条"格式错误"日志，然后就过去了。

**结论：MAXLEN 在本项目里的唯一作用，是在最需要保住数据的时刻把数据裁掉。**

### 期望改动

1. 删除 `line 3` 的 `STREAM_MAX_LEN` 定义。
2. 删除 `line 33` 的三个参数 `'MAXLEN', '~', STREAM_MAX_LEN`。
3. 在 `XADD` 上方补一两行注释，说明**为什么故意不加 MAXLEN**。
   要点自己组织，必须覆盖：已处理条目由消费端 XDEL 清理；
   消费停摆时必须保住 pending 消息，宁可让 Redis 涨、也不要静默丢数据。

改动参考（不要照抄，自己确认等价）：

```lua
-- 故意不用 MAXLEN 裁剪：处理完的条目由消费端 XACK + XDEL 清理，
-- 而裁剪不区分是否仍在 PEL 中，消费停摆时会删掉尚未落库的消息。
redis.call('XADD', KEYS[4], '*',
    'userId', ARGV[2],
    ...
```

### 验收（必须给实际输出）

a) 用一个**测试用** stream key（例如 `remark:like:changes:selftest`）跑一次
   Lua，确认 XADD 语法正确、能写入：

   ```
   redis-cli --eval <lua文件的绝对路径> <KEYS[1..4]> , <ARGV[1..4]>
   ```

   跑完 `XLEN remark:like:changes:selftest` 应该 >= 1。**验完 DEL 掉这个测试 key。**

b) 往测试 stream 连写 20000 条，确认 `XLEN` 会涨到 20000 左右
   （不再被裁到 10000）。不需要用 Lua，直接 `XADD` 循环即可。

c) **绝对不要碰生产 key** `remark:like:changes` 和
   `remark:like:changes` 所属的消费组。只用测试命名空间。

如果你**无法访问 Redis**（比如缺少连接配置），如实说明"验收 a/b 未执行，
原因：xxx"。不要贴假的输出。

---

## 2. P1-2 发布失败分类（可重试 / 不可重试）

### 涉及文件

`tj-remark/src/main/java/com/tianji/remark/service/LikeTimesPublisher.java:44-54`

### 现状

```java
line 46:  CorrelationData.Confirm confirm = correlationData.getFuture()
line 47:          .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
line 48:  if (!confirm.isAck()) {
line 49:      throw new IllegalStateException("RabbitMQ 未确认点赞消息：" + correlationData.getId());
line 50:  }
line 51:  if (correlationData.getReturned() != null) {
line 52:      throw new IllegalStateException("点赞消息没有匹配的队列绑定：" + correlationData.getId());
line 53:  }
```

**三种性质完全不同的失败被一视同仁**：

| 情况 | 行号 | 性质 | 重试有意义吗 |
|------|------|------|--------------|
| `!confirm.isAck()` | 48-50 | Broker 未确认（限流 / 抖动） | ✅ 有 |
| `future.get(...)` 抛 `TimeoutException` | 46-47 | Broker 响应超时 | ✅ 有 |
| `getReturned() != null` | 51-53 | **没有匹配队列 = 配置问题** | ❌ **完全没有** |

第三行是本次要修的重点：路由失败时消息根本没进任何队列，
重试一万次结果都一样。现在它和临时故障一样报同一种异常，
日志里也看不出"该去建队列"这个动作。

### 期望改动

1. 新增两个异常类型（包路径你自己定，交付说明里写清楚放在哪）：
   - `LikePublishRetryableException` —— 可重试
   - `LikePublishUnroutableException` —— 不可重试
   两者都应该是 `RuntimeException` 的子类。如果项目里已有异常基类，复用。

2. 按上表分类抛出：
   - `!confirm.isAck()` → `LikePublishRetryableException`，消息带 `correlationData.getId()`
   - `get(...)` 抛 `TimeoutException` → `LikePublishRetryableException`
   - `getReturned() != null` → `LikePublishUnroutableException`

3. **路由失败的异常信息必须可操作**，至少包含：
   - 出问题的 `routingKey`（在 `publish` 方法里能算出来，见 `line 29-30`）
   - 一句明确的动作提示，例如「该 routing key 没有匹配队列，检查下游是否已声明队列」

4. 调用方 `LikeWriteBehindTask.java:52-61` 的 catch 分开处理：

   ```java
   } catch (LikePublishUnroutableException e) {
       // 配置问题、不是 bug，打堆栈没有信息量，反而把关键信息埋了
       log.error("点赞消息无法路由，管线暂停直到队列恢复：{}", e.getMessage());
   } catch (Exception e) {
       log.warn("处理点赞缓存批次失败，将按退避策略重试", e);
   }
   ```

   注意：**两种情况都要保持"不 ACK"** —— 数据不丢优先于管线畅通。
   这里只是改日志级别和内容，不改变控制流。

5. 变量命名不要和方法名重名（上一批 `LikeChangePersistenceService` 里
   `String recordKey = recordKey(change);` 是个反面例子，别再犯）。

### 验收（必须给实际输出）

单元测试：构造 `CorrelationData`，分别模拟
`ack=false` 和 `returned != null` 两种返回，
断言抛出的异常类型正确、且 Unroutable 的 message 里含 routingKey。

然后实际执行 `mvn test`，贴出真实输出（含 `Tests run:` 那行）。

---

## 3. P1-3 指数退避（避免永久失败时每秒刷屏）

### 涉及文件

`tj-remark/src/main/java/com/tianji/remark/task/LikeWriteBehindTask.java:41-62`

### 现状

```java
line 41:  @Scheduled(fixedDelayString = "${tj.like.flush-interval-ms:1000}")
line 42:  public void flushChangesAndPublish() {
...
line 54:      } catch (Exception e) {
line 55:          // 数据库或 MQ 失败时保留 Stream 的 pending 消息，下一轮从头重试这一批。
line 56:          log.error("处理点赞缓存批次失败", e);
```

调度是固定 1 秒一轮。一旦出现**永久性失败**（比如 P1-2 里的路由失败，
或者数据库长期不可用），就会每秒重试同一批、每秒打一条堆栈，
日志被刷爆，真正有用的信息被埋掉。

### 期望改动

1. 连续失败时按指数退避重试：`1s → 2s → 4s → 8s → …` **封顶 60s**。
2. **成功一次立刻清零**，回到正常的 1s 周期。
3. 处于退避窗口内时，方法**直接 return**，不要 sleep、不要阻塞调度线程。
4. **把退避逻辑抽成一个独立的小类**（名字自定，例如 `PublishBackoff`），
   只做纯计算，**不依赖 Redis / Rabbit / Spring 容器**：
   - 记录连续失败次数
   - 算出下次可执行时间
   - 判断"现在是否应该跳过"
   - 成功时清零
   抽出来的目的是**可单测** —— 这也是本项的验收方式。
   在 `LikeWriteBehindTask` 里以 Spring Bean 或字段形式持有它。
5. 参数从配置读，给默认值（写在 `@Value` 或配置类里都行）：
   - `tj.like.retry.backoff-base-ms` 默认 `1000`
   - `tj.like.retry.backoff-max-ms` 默认 `60000`
6. 只做"退避"，**不要引入重试上限、不要丢弃、不要死信**。
   「一直重试，但间隔越来越长」不是丢数据，这是本项的设计前提。

### 验收（必须给实际输出）

针对第 4 步抽出来的那个类写单元测试：

- 连续失败 1 / 2 / 3 次后，下次可执行时间分别是 `base*1 / base*2 / base*4`
- 连续失败 20 次后，退避时间**封顶在 max**，不会溢出成负数
- 成功一次后，失败计数归零、退避回到 base

然后实际执行 `mvn test`，贴出真实输出。

---

## 4. 不要做（本批明确排除，不要顺手改）

以下都是**已知问题、本批故意不做**。看到它们请忽略，不要"顺便优化"：

- ❌ 不要加启动自检 / 队列存在性校验（需要引入 bizType↔队列名的硬编码映射，
  本批评估后决定不做）
- ❌ 不要加对账任务、不要加数据修复逻辑
- ❌ 不要加 Prometheus / Micrometer / 任何监控埋点
- ❌ 不要加死信队列、隔离 Stream、重投任务
- ❌ 不要动 `remark.note.liked.times.queue` 相关的一切
- ❌ 不要动 `LikeCacheInitializer` 的初始化逻辑（上一批刚改完）
- ❌ 不要给 `LikeCacheService` 加降级路径（现在没有降级，本批不加）
- ❌ 不要清理 `versionHash`（`HSET` 写了但 `ZREM` 时没有 `HDEL`，已知，以后再说）
- ❌ 不要改 `LikeCacheKeys` 里的键结构或键名
- ❌ 不要动 `LikeChangePersistenceService` 的落库逻辑（上一批刚改完）
- ❌ 不要动 `tj-remark` 之外的任何模块
- ❌ 不新增依赖、不改 pom

---

## 5. 交付格式

每个 P1 项给三段：

1. **改了什么** —— 文件路径 + 行号 + 改动后的代码片段
2. **执行了什么命令** —— 原样贴命令
3. **实际输出是什么** —— 原样贴输出（`Tests run:` 行、`XLEN` 结果等）

最后附一段：**你发现但按规格没动的问题**（如果有，列出来；
这一步很重要，说明你确实读完约束了）。

不要只写"已完成"。验收没做成就写"未做成 + 原因"。
