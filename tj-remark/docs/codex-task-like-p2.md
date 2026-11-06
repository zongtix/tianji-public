# 第一批任务规格（P2）—— 恢复正确性与坏记录治理

> 上一批（P1）已交付：移除 Lua 的 MAXLEN、发布失败分类、指数退避。
> 本批对应上游计划的「第一批：恢复正确性」+「第一批：坏记录与加固」两节，
> **两节必须同批交付**，因为 A-1 是 A-2 停写语义成立的前提。

---

## 0. 总约束

沿用 P1 的 6 条（只改列出的文件 / 不改对外接口与键结构 / 不新增依赖 /
必须给实际命令与实际输出 / **不要执行任何 git 命令** / 注释只解释为什么）。
本批新增 3 条：

7. **顺序不可颠倒**：A-1（Lua 原子检查 READY）必须先落地并验证通过，
   A-2（停写排空重建）才能启用。否则停写窗口对写入不封闭。
8. **除 B-1 的死信表外，不允许任何表结构变更**，不允许改 Redis 键名/键结构。
9. **禁止把 A-2 的降级语义扩散到正常消费路径。**
   `LikeWriteBehindTask.flushOneStreamBatch()` 现有的
   「落库 → 发 MQ → 再 ACK」顺序是已确认正确的设计，本批不动。
   降级只适用于「排空路径」（原因见 D1）。

---

## 1. 已确认决策（不要重新讨论、不要提出替代方案）

| # | 决策 | 依据 |
|---|------|------|
| D1 | **排空阶段遇到 MQ 故障允许降级**：排空的完成条件是「落库完成」，不是「MQ 确认完成」 | MQ 故障本身就是积压的成因。若排空等 MQ confirm（`LikeTimesPublisher:49` 超时 5s 抛异常），必然出现「MQ 不修好 → 排空排不完 → READY 永不恢复 → 服务永久拒绝写入」的启动死锁。已由项目负责人确认 |
| D2 | 死信落 **MySQL 表**，不使用 Redis Stream | 死信 Stream 无法设 MAXLEN（P1-1 已证明 MAXLEN 会裁掉仍在 PEL 的条目），会无限增长；且「可定位」要求长期保留 |
| D3 | 排空完成判据 = 「不存在尚未落库的**可解析**记录」；剩余未 ACK 的条目必须**全部已被标记为坏记录** | 覆盖「已写入但从未投递」与「已投递未 ACK」两个来源。**禁止**为了凑齐 `XLEN==0 / XPENDING==0` 而 ACK 掉不可解析的记录（见 A9） |
| D4 | 本批不改 NOTE 入口与兜底队列 | NOTE 是否上线单独决策，不在本批范围 |

---

## 2. 动手前的实测基线（先自己核对一遍，不要凭记忆）

| 项 | 实测值 | 影响 |
|---|---|---|
| Redis 版本 | **6.2.6** | **`XAUTOCLAIM` 不可用**（6.5+ 才引入），接管别人 pending 只能用 `XCLAIM` |
| `maxmemory` / `maxmemory-policy` | `0` / `noeviction` | 不淘汰，写满即写失败 |
| 部署形态 | `standalone` 单点，无主从 | 无故障转移 |
| `remark:like:cache:ready` | `READY` | — |
| `XLEN remark:like:changes` | `0` | — |
| group `remark-like-db-writer` | consumers=1（`remark-like-writer`），pending=0，last-delivered-id=`1790255391766-0` | 当前无孤儿 pending |
| **关键现象** | **`XLEN == 0` 但 `remark:like:changes` key 仍存在** | 带 consumer group 的空 Stream 不会被 Redis 回收 → 直接导致 A-3 的问题**现在就在发生** |

---

## A 节：恢复正确性

### A-1 把 READY 检查放进写入 Lua，固定返回码

**文件**：
- `tj-remark/src/main/resources/redis/like-change.lua`
- `tj-remark/src/main/java/com/tianji/remark/service/LikeCacheService.java:36-55`

**现状**：`LikeCacheService:38-40` 先 GET 检查 READY，`:50` 才执行 Lua，
检查与写入之间不是原子的。当前靠「初始化只在 state != READY 时清理」隐式避开，
但 A-2 的停写流程**会主动把 READY 置为 BUILDING**，这个窗口就从"理论"变成"可达"。

**期望改动**：

1. Lua 的第一个动作检查 READY 标记，不满足立即返回 `-1`，**不做任何写入**。
2. 为此 `KEYS` 需要新增一个（READY key），即从 4 个变 5 个，
   `LikeCacheService:43-47` 的 keys 列表同步调整。**KEYS 顺序写进注释**。
3. 返回码约定固定为：

   | 返回 | 含义 | Java 侧行为 |
   |---|---|---|
   | `1` | 状态确实改变（已写 Set/ZSet/version/Stream） | 正常返回 |
   | `0` | 幂等无变化（重复点赞/重复取消） | 正常返回（接口成功） |
   | `-1` | **未 READY** | 抛 `LikeServiceUnavailableException`（见 B-3） |

4. **`-1` 绝不能复用 `0`。** 现在是 `:52-54` 只判 `null`，
   返回 `0` 被当作幂等成功静默返回 200。若 READY 未就绪也用 0，
   用户会看到「点赞成功」但什么都没写 —— 这正是本批要消灭的行为。

**验收**：见 §3 A1 / A2 / A12。

---

### A-2 停写 → 排空 → 重建 的恢复流程

**文件**：
- `LikeCacheInitializer.java`（主改动）
- `LikeWriteBehindTask.java`（抽出可复用的「解析 + 落库」部分，供排空路径调用）

**现状**：`LikeWriteBehindTask:58` 在非 READY 时直接 `return`，
所以 `BUILDING` 期间**没有任何消费发生** —— 需要一条专门的恢复消费路径。
（这一点上游计划判断正确。）

**期望流程**（步骤号在交付说明里原样引用）：

```
S1  取 INITIALIZE_LOCK
S2  二次检查：若 state == READY 直接返回（避免重复恢复）
S3  SET ready = BUILDING          ← 此刻起写入被 A-1 的 Lua 原子拒绝（返回 -1）
S4  排空循环：
      loop:
        pending = read(consumer, count=500, ReadOffset.from("0-0"))      // 本 consumer 的 PEL
        fresh   = read(consumer, count=500-pending.size, lastConsumed()) // 新消息：每轮都读，不等 PEL 空
        records = pending + fresh
        if records 为空: break
        解析：合法 → 累积 changes；非法 → 写死信（B-1），累积 deadLetters
        落库：persistBatch(changes)                                  // 事务
        发布：publish(snapshots)，失败记入 failedSnapshots 且【不抛出】  // ← D1 降级点
        确认：XACK(已落库 + 已进死信的 id)；XDEL(同一批 id)
      清除幽灵 PEL 条目（见下方 ★）
      if 轮次 > 上限: 抛异常 + ERROR 日志，不要置 READY
S5  判据（D3，修正后）：不存在尚未落库的**可解析**记录，即
      - 已 ACK 之外，Stream / PEL 中剩余的条目**全部**是「已成功进死信的坏记录」
        或「已标记、等待人工处理的坏记录」
      - **不要求** XLEN==0 / XPENDING==0 —— 坏记录故意留在原地（见 A9）
      - 只要还存在「可解析但未落库」的记录 → 回到 S4
S6  重建：clearLikeNamespace() → loadExistingRecords()
      ★ 清理时除 READY_KEY、CHANGE_STREAM 外，还必须跳过 version:*（见下方 ★★）
S7  补发：只对 failedSnapshots 涉及的 bizId，best-effort 再发一次
      - count 必须从【重建后的 ZSet】读取，不能用消息里的旧 likedTimes
      - version 从 versionHash 读取；缺失则用当前时间的微秒值
      - 重发 N 次仍失败：ERROR + 告警，【不阻塞】后续步骤
S8  SET ready = READY
```

**必须注意的四条**：

★ **PEL 非空不得阻塞新消息读取（本批必须修，是 A-2 与 A9 的共同前提）。**
现状 `LikeWriteBehindTask:88-95` 是「先读 PEL，**只有 PEL 为空才读新消息**」，
所以任何一条永不 ACK 的记录都会让新消息**永远读不到**，整条落库管线停摆。
**这才是必须解决的问题本身**，而 A9 的坏记录恰好就是这样一条记录。
修法：每轮**都**读一批 PEL + 一批新消息。一次 `XREADGROUP` 无法同时读两者，
需要两次调用，用剩余配额给新消息，保证单轮总量仍 ≤ `STREAM_BATCH_SIZE`：

```java
List<MapRecord<String, Object, Object>> records = new ArrayList<>();
List<MapRecord<String, Object, Object>> pending = streams.read(consumer,
        StreamReadOptions.empty().count(STREAM_BATCH_SIZE),
        StreamOffset.create(LikeCacheKeys.CHANGE_STREAM, ReadOffset.from("0-0")));
if (pending != null) records.addAll(pending);
if (records.size() < STREAM_BATCH_SIZE) {          // 仅用剩余配额读新消息
    List<MapRecord<String, Object, Object>> fresh = streams.read(consumer,
            StreamReadOptions.empty().count(STREAM_BATCH_SIZE - records.size()),
            StreamOffset.create(LikeCacheKeys.CHANGE_STREAM, ReadOffset.lastConsumed()));
    if (fresh != null) records.addAll(fresh);
}
```

这不是降级，是修复读取逻辑缺陷；正常路径与排空路径共用同一段。

★ **幽灵 PEL 条目会让判据永远不成立。**
Redis 6.2 的 `XDEL` 不会自动清理 PEL 里的引用（`KEEPREF`/`DELREF` 是 8.2 才有的）。
所以会出现「`XLEN == 0` 但 `XPENDING 汇总 != 0`」：那些 pending 条目指向的消息已经不存在了。
处理方式：接管后读到的是 `nil` → `XACK` 掉即可清除。
**没有这一步，S5 判据会死循环。**

★ **孤儿 consumer 的 pending 必须接管。**
`ReadOffset.from("0-0")` 只能读到**本 consumer**（`remark-like-writer`）的 PEL。
排空前先执行 `XPENDING <stream> <group> - + 100` 列出汇总，
若发现 consumer 名不是 `remark-like-writer` 的条目 → 用 `XCLAIM` 接管
（**Redis 6.2.6 没有 `XAUTOCLAIM`，不要写它**）。
`XCLAIM` 的 `min-idle-time` 传 0，因为消息已经停了，不需要等空闲时间。

★★ **S6 必须保留 `version:*`。**
`versionHash` 是「防止下游拒绝新消息」的单调版本基线（`like-change.lua:24-30`
的 `max(timeVersion, lastVersion+1)` 依赖它）。重建时清掉它，
S7 的补发就没有版本可用，且版本单调性被重置。
把 `version:*` 和 `CHANGE_STREAM` 一样列入清理跳过名单，并写注释说明原因。

**关于 S4 的发布降级（D1）**：
正常路径 `flushOneStreamBatch()` 保持不变（MQ 失败 → 抛异常 → 不 ACK → 退避重试）。
排空路径才允许「发布失败不阻塞」。
实现上建议给内部的解析/落库方法加一个参数区分两种策略，
**不要复制两份落库代码，也不要改正常路径的控制流**。

**验收**：§3 A3 / A4 / A5 / A6 / A7。

---

### A-3 `hasLikeDataKeys` 排除 `changes`，READY 丢失改为走恢复流程

**文件**：`LikeCacheInitializer.java:54-55` 与 `:146-163`

**现状（现在就在发生的风险）**：
`hasLikeDataKeys()` 只跳过 `READY_KEY`，**不跳过 `CHANGE_STREAM`**。
而实测 `remark:like:changes` 这个 key **永久存在**（`XLEN == 0` 也还在，
因为带 consumer group 的空 Stream 不会被回收）。
所以该方法**恒为 true**，于是：

> 任何一次 `remark:like:cache:ready` 被误删或单 key 丢失
> → 下次启动 `:54-55` 抛 `IllegalStateException`
> → **应用永久起不来**，只能人工干预。

这与 `clearLikeNamespace:128-131` 故意保留 `changes` 的策略**自相矛盾**
（一边要保留待落库变更，一边把它的存在当成"残留数据异常"）。

**期望改动**：

1. `hasLikeDataKeys()` 扫描时**同时跳过** `READY_KEY` 和 `CHANGE_STREAM`。
2. `:54-55` 的「拒绝启动」改为**进入恢复流程**（A-2 的排空 + 重建现在能安全处理这个场景）。
3. 保留安全边界：只有在 `user:*` / `count:*` 真的存在时才触发恢复，
   否则走正常的空初始化和全量加载。

**验收**：§3 A11。

---

## B 节：坏记录与加固

### B-1 坏记录进死信（写入失败则原记录保留）

**文件**：`LikeWriteBehindTask.java:100-111`，新增死信表与写入类

**现状**：

```java
recordIds.add(record.getId());              // :103 无条件加入
LikeChange change = toChange(record.getValue());
if (change == null) {
    log.error("忽略格式错误的点赞变更记录，streamId={}", record.getId());   // :107
}
```

坏记录被计入 `recordIds`，在 `:117-120` 一起 `XACK` + `XDEL` ——
**原始内容被静默删除**，而 `liked_record` 是唯一数据源，该次点赞永久丢失且无线索。

**期望改动**：

1. **新增死信表**（本批唯一的表结构变更，DDL 见下），
   落点 `tj-remark/src/main/resources/db/` 下新建一个 sql 文件。
2. 解析失败的记录 → 写死信（`stream_id` + 原始字段键值对 + 失败原因）→
   **死信写入成功后**才 `XACK` + `XDEL`。
3. **死信写入失败 → 重试，且【不 ACK、不 XDEL】，原记录永久保留。**

   > 上一版规格在这里写过「重试 N 次仍失败就 ACK 并丢弃」的逃逸阀，
   > **该设计已撤回**，不要实现它。理由：
   >
   > - 死信写入失败**不是偶发抖动**。正常落库 `persistBatch` 与死信写的是同一个库，
   >   所以「落库成功、死信失败」只可能由**死信表自身的配置 / DDL 问题**造成 ——
   >   那是**永久性**失败。重试上限一到，逃逸阀就从"最后手段"退化成
   >   「每一条坏记录都被丢掉」的常态路径。
   > - `liked_record` 是唯一数据源，ACK 之后原始变更**不可恢复**。
   > - 停摆是 **fail-safe**（数据仍在 Redis，可观测、可恢复）；
   >   丢弃是 **fail-open**（永久消失）。
   > - 所以正确形态是「**保留原记录 + 解除它对新消息的阻塞**」（A-2 的 ★），
   >   *不是*「丢弃记录让管线跑起来」。

   重试策略：连续失败按指数退避（复用 P1-3 的退避思路，内存中按 `streamId` 记失败次数），
   **退避只降低重试频率，不改变"永不 ACK"这个事实**。
   失败必须可观测：ERROR 日志含**原始 payload 全文**（指标埋点归第二批）。
   死信表恢复正常后，下一轮写入成功 → 立即 `XACK` + `XDEL` → 管线自动回到完全正常，
   **无需人工补偿**。
4. `toChange()` 的失败原因要可区分：缺字段 / 数字非法 / bizType 非法 / liked 非法
   （`toChange` 现在把所有 `RuntimeException` 吞成 `null`，看不出原因）。

DDL 参考（表结构自行确认，字段满足「可定位」即可）：

```sql
CREATE TABLE IF NOT EXISTS liked_change_deadletter (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    stream_id      VARCHAR(32)  NOT NULL COMMENT '原始 Stream 记录 ID',
    payload        TEXT         NOT NULL COMMENT '原始字段键值对，JSON',
    fail_reason    VARCHAR(128) NOT NULL COMMENT '缺字段/数字非法/bizType非法/liked非法',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_stream_id (stream_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞变更死信，人工排查用';
```

**验收**：§3 A8 / A9。

---

### B-2 `liked` 严格校验（防止坏值被当成取消点赞）

**文件**：`LikeWriteBehindTask.java:129`

**现状**：

```java
change.setLiked("1".equals(value(values, "liked")));
```

任何非 `"1"` 的值（`null` / `""` / `"abc"` / `"true"`）都被解释为 `false`，
而 `false` 会走 `LikeChangePersistenceService:91-108` 的 **`DELETE FROM liked_record`**
—— 一条坏记录会**删掉真实数据**。

**期望改动**：显式三分支，非法值绝不落库、走死信：

```java
String liked = value(values, "liked");
if ("1".equals(liked)) {
    change.setLiked(true);
} else if ("0".equals(liked)) {
    change.setLiked(false);
} else {
    return null;   // 交给 B-1 的死信路径，绝不当作"取消点赞"
}
```

**说明**：正常流下 Lua 只写 `"1"`/`"0"`（`LikeCacheService:51`），
所以真实可达路径是**滚动发布期间新旧 Lua 格式不一致**。
这是零成本的防御性加固，不要因为它"现在打不到"就跳过。

**验收**：§3 A10。

---

### B-3 503 / 400 的错误约定落地

**文件**：新增异常类、`LikeCacheService.java:38-40`、`LikedRecordController.java:42,50`

**现状一：503 不存在，现在是 500。**
`LikeCacheService:39` 抛的是 JDK 的 `IllegalStateException`
→ 落到 `CommonExceptionAdvice:79-83` 的兜底分支
→ 对外是 **500「服务器内部异常」**，不是 503。
而 `CommonException.getStatus()` **硬编码返回 500**（`CommonException.java:29-31`），
所以必须新增子类覆盖 `status`。

期望：新增 `LikeServiceUnavailableException extends CommonException`，
照 `UnauthorizedException.java:7-13` 的写法（`private final int status = 503;`
+ `super(503, message)`）。把 `LikeCacheService` 三处 READY 校验
（`:38-40`、`:60-62`、`:89-91`）以及 A-1 的 `-1` 分支都改抛它。

**现状二：网关/直连的返回差异已经实现，不要改框架。**
`CommonExceptionAdvice:85-93` 已经做了：网关请求 → HTTP 200 + `R.error(code, msg)`；
直连 → `ResponseEntity.status(status).body(msg)`。**这部分不需要动。**

**现状三：NOTE 的 400 在 GET 路径上直连拿不到 400。** 三条路径行为不一致：

| 入口 | 异常 | 处理分支 | 网关 HTTP / 业务码 | 直连 HTTP |
|---|---|---|---|---|
| `POST /likes` + NOTE（`LikeRecordFormDTO:22`） | `MethodArgumentNotValidException` | `:47-55` | 200 / 400 | **400** ✔ |
| `GET /likes/status`、`/times` + NOTE（`LikedRecordController:42,50`） | `ConstraintViolationException` | `:70-77` | 200 / 400 | **200** ✘ |

原因：`:74` 传的是 `HttpStatus.OK.value()`。

**处理要求**：
- **不要修改 `CommonExceptionAdvice`** —— 它是全仓库共用的，
  影响所有使用 `@Validated`/`@Pattern` 的接口，风险远超本批范围。
- 在 Controller 层解决：让 GET 两个接口的非法 `bizType` 直连也返回 400。
  可行的做法是把校验从 `@Pattern` 挪到方法体内显式抛 `BadRequestException`
  （`status=400` + `super(400, msg)`），**但注意**
  `LikedRecordServiceImpl:43-45` 在 `bizIds` 为空时会先 return、绕过 service 层校验，
  所以校验必须放在 Controller 里、在任何提前返回之前。
- 交付说明里写清你选了哪种方式、为什么。

**对外错误约定汇总（本批最终形态，写进交付说明）**：

| 情况 | 业务码（网关） | 直连 HTTP |
|---|---|---|
| Lua 返回 `-1`（未 READY） | 503 | 503 |
| Redis 不可用 / 连接失败 | 503 | 503 |
| `bizType` 非法（POST / GET） | 400 | 400 |
| Lua 返回 `0`（幂等无变化） | 成功 | 200 |
| 未登录 | 401（现状 `UnauthorizedException`） | 401 |

**验收**：§3 A13。

---

## 3. 验收汇总

`类型`：单测 = JUnit 可覆盖；集成 = 本地起 remark + 真实 Redis/MQ；环境 = 需要真实环境操作。

| # | 用例 | 类型 | 操作 | 判据 |
|---|---|---|---|---|
| A1 | 未 READY 写入被拒 | 单测 | `SET ready=BUILDING` 后调 `change()` | 抛 `LikeServiceUnavailableException`；Redis 无新 Set/ZSet 变化；`XLEN` 不变 |
| A2 | 幂等无变化返回 0 | 单测 | 连续两次同状态 `change()` | 第二次 Lua 返回 0，接口成功，`XLEN` 不增长 |
| A3 | 排空覆盖 > 一批（500） | 集成 | 造 600 条积压后触发恢复 | 全部落库；`XLEN==0 && XPENDING 汇总==0`；MySQL 关系数与 ZSet 计数一致 |
| A4 | pending 与未投递并存 | 集成 | 造 N 条 pending + M 条未投递 | 两个来源都被处理；缺任一条判据都不算完成 |
| A5 | 孤儿 consumer 的 pending | 环境 | 用第二个 consumer 名读一批且不 ACK，再触发恢复 | `XCLAIM` 接管后落库，汇总 pending 归零 |
| A6 | 幽灵 PEL 不导致死循环 | 集成 | 构造「`XLEN==0` 但汇总 pending != 0」 | 接管读到 nil → `XACK` 清除 → 判据成立，流程正常结束（**不是超时/超轮次退出**） |
| A7 | MQ 故障下排空降级（D1） | 环境 | 停掉 RabbitMQ → 造积压 → 触发恢复 | 排空与重建完成、`READY` 恢复；MQ 恢复后 failedSnapshots 补发成功；**服务没有卡在 BUILDING** |
| A8 | 坏记录进死信 | 集成 | 手工 `XADD` 一条缺字段的记录 | 死信表有原文 + `stream_id`；原记录被 `XACK`+`XDEL`；同批正常记录照常落库 |
| A9 | 死信写入失败**不丢数据** | 集成 | 让死信写入连续失败 | 原记录**仍在 PEL / Stream 里**（`XLEN`、`XPENDING` 都不减）；ERROR 日志含原文；**新消息继续被处理、管线不停摆**；修好死信表后该记录自动被 `XACK`，无需人工补偿 |
| A14 | PEL 非空不阻塞新消息 | 集成 | 先造 1 条永不 ACK 的记录，再写入普通点赞 | 普通点赞在下一轮就被落库；`XLEN` 因坏记录不为 0 但管线正常 |
| A10 | 坏 `liked` 不误删 | 单测 | `XADD liked=abc` | 该记录走死信；**不执行 `DELETE FROM liked_record`**；真实记录仍在 |
| A11 | READY 丢失可自愈 | 环境 | `DEL remark:like:cache:ready` 后重启 | 走恢复流程起服务，**不抛 `IllegalStateException`** |
| A12 | 并发重建不受写入 | 集成 | 恢复过程中并发 `POST /likes` | 全部返回 503；被清理的 key 无残留写入；`XLEN` 无新增 |
| A13 | 503 / 400 返回码 | 集成 | 分别走网关与直连 | 与 B-3 的约定表逐行一致 |

**如实交付**：做不成的用例写「未执行 + 原因」，不要贴编造的输出。

---

## 4. 不要做（本批明确排除）

- ❌ 不要把 D1 的降级语义加到正常消费路径（`flushOneStreamBatch` 的
  「落库 → 发 MQ → 再 ACK」顺序禁止改动）
- ❌ 不要修改 `CommonExceptionAdvice`（全仓库共用）
- ❌ 不要给 Stream 加 `MAXLEN`（P1-1 已论证它会裁掉 PEL 中的未落库消息）
- ❌ **任何情况下都不要 `XACK` / 丢弃不可解析或死信写入失败的记录**
  （A9 的逃逸阀设计已撤回，见 B-1 第 3 点）
- ❌ 不要处理 `versionHash` 在 `ZREM` 时不 `HDEL` 的遗留项（另批）
- ❌ 不要给 `remark.note.liked.times.queue` 加 TTL / max-length / 消费者（见 D4）
- ❌ 不要加监控埋点、对账任务、Prometheus（第二批）
- ❌ 不要做压测、不要改发布流程（第三批）
- ❌ 除死信表外不要有任何表结构变更；不要改 Redis 键名
- ❌ 不要执行任何 git 命令

---

## 5. 交付格式

每项给四段（沿用 P1，新增第 4 段）：

1. **改了什么** —— 文件路径 + 行号 + 改动后的代码片段
2. **执行了什么命令** —— 原样贴
3. **实际输出是什么** —— 原样贴（`Tests run:`、`XLEN`、`XPENDING`、Redis 版本等）
4. **对应用例** —— 填 §3 的编号，做不成的写清原因

最后附一段：**你发现但按规格没动的问题。**

不要输出架构说明、不要输出 mermaid、不要输出"改进建议"。
