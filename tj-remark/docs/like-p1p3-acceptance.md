# 点赞模块 P1–P3 真实环境验收手册

**覆盖范围**：`tj-remark` 的 Redis 写缓冲 + Stream 异步落库（P1 发布可靠性 / P2 恢复与死信 / P3 判据与降级）。

**为什么还需要这份手册**：27 个单测（`mvn -pl tj-remark -am test`）只能证明「逻辑写得对」，证明不了「真实 Redis 6.2.6 + MySQL 上跑起来对」。下列验收项全部是单测无法覆盖的部分。

**环境形态**：本地 IDEA 起 `tj-remark`（`local` profile，端口 **8091**）→ 经 SSH 隧道连服务器 Redis 6.2.6 + MySQL 8。不部署、不动生产容器。

**约定**：每次验收前先跑第 0 节；每条验收独立可回退；全部测完跑第 8 节清理。

---

## 0. 前置检查与基线

### 0.1 变量

```bash
WINIP=$(ip route show default | awk '{print $3}')     # WSL 打 Windows 上的服务要用网关 IP
RHOST=developer@tianji-dev.example.invalid
R="docker exec redis redis-cli -a 123321 --no-auth-warning"
```

隧道判活（**必须用 Windows 侧探针**，WSL 的 `curl`/`ss` 测不到 Windows 回环端口）：

```bash
/mnt/c/Windows/System32/netstat.exe -ano | tr -d '\r' | grep LISTENING | grep -E ':(3306|6379|8848) '
# 期望：3306 / 6379 / 8848 各有监听
```

### 0.2 服务起来了吗

```bash
timeout 5 curl -s -o /dev/null -w "8091 → %{http_code}\n" "http://$WINIP:8091/likes/status?bizType=QA" -H "user-info: 2"
# 期望 200。404/000 = 服务没起或端口不对
```

启动顺序：先确认隧道 → 起 Nacos 侧依赖（`shared-redis.yaml` / `shared-mybatis.yaml` 在 Nacos `public` 命名空间）→ 再起 `tj-remark`（`-Dspring.profiles.active=local`）。

命令行启动（备选）：先 `mvn -pl tj-remark -am install -DskipTests`，再 `cd tj-remark && mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8"`。
⚠️ 不要用 `mvn -pl tj-remark spring-boot:run`，父 pom 会插进来跑、目标模块反被 SKIPPED。

### 0.3 Redis 基线（记下来，后面要对比）

```bash
ssh $RHOST "$R GET remark:like:cache:ready; $R XLEN remark:like:changes; $R XINFO GROUPS remark:like:changes; $R KEYS 'remark:like:*'"
```

验收开始时的实测基线：

| 项 | 值 |
|---|---|
| `remark:like:cache:ready` | `READY` |
| `remark:like:changes` XLEN | `0` |
| group `remark-like-db-writer` | consumers 1 / pending 0 / last-delivered-id `1790255391766-0` |
| like 相关 key | `remark:like:{cache:ready, changes, count:QA, user:QA:2}` |

### 0.4 MySQL 基线

```bash
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" --table' <<'SQL'
USE tj_remark;
SELECT COUNT(*) AS total FROM liked_record;
SELECT biz_type, COUNT(*) AS cnt FROM liked_record GROUP BY biz_type;
SELECT COUNT(*) AS deadletter_total FROM liked_change_deadletter;
SQL
```

实测基线：`liked_record` **1 行**（`id=9, user_id=2, biz_id=1585177426969833473, QA`）；`liked_change_deadletter` **0 行**。

### 0.5 用哪个 userId 测

**统一用 `user-info: 9999`**（一个不存在的用户）。理由：测试数据全归到 9999，清理时按用户删一遍就干净，不会污染他自己在页面上产生的真实点赞（`user_id=2`）。

---

## 1. 正常链路 + 幂等（1 / 0 两个返回码）

**目的**：证明「Lua 返回 1 = 状态改变」和「返回 0 = 幂等无变化」在真实链路上分别成立，且变更最终落到 MySQL。

```bash
BIZ=999001

# ① 首次点赞 → 期望 HTTP 200
curl -s -o /dev/null -w "点赞 → %{http_code}\n" -X POST "http://$WINIP:8091/likes" \
  -H "Content-Type: application/json" -H "user-info: 9999" \
  -d "{\"bizId\":$BIZ,\"bizType\":\"QA\",\"liked\":true}"

# ② 立刻看 Redis（1 秒 flush 窗口内）
ssh $RHOST "$R SISMEMBER remark:like:user:QA:9999 $BIZ; $R ZSCORE remark:like:count:QA $BIZ; $R XLEN remark:like:changes"
# 期望：1 / 1 / 1

# ③ 等落库
sleep 2
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" --table -e "SELECT id,user_id,biz_id,biz_type FROM tj_remark.liked_record WHERE biz_id=999001"'
# 期望：1 行
ssh $RHOST "$R XLEN remark:like:changes"
# 期望：0（落库成功后已 XDEL）
```

**幂等（关键）**：重复发一次同样的请求

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://$WINIP:8091/likes" \
  -H "Content-Type: application/json" -H "user-info: 9999" \
  -d "{\"bizId\":999001,\"bizType\":\"QA\",\"liked\":true}"
ssh $RHOST "$R ZSCORE remark:like:count:QA 999001; $R XLEN remark:like:changes"
# 期望：still 1 / 0  —— 计数没有被加两次，也没有产生新的 Stream 记录
```

**取消赞**：

```bash
curl -s -o /dev/null -w "取消 → %{http_code}\n" -X POST "http://$WINIP:8091/likes" \
  -H "Content-Type: application/json" -H "user-info: 9999" \
  -d "{\"bizId\":999001,\"bizType\":\"QA\",\"liked\":false}"
ssh $RHOST "$R SISMEMBER remark:like:user:QA:9999 999001; $R ZSCORE remark:like:count:QA 999001"
# 期望：0 / (nil)  —— count<=0 时 ZREM 掉了成员（like-change.lua:23-27）
sleep 2
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" -N -e "SELECT COUNT(*) FROM tj_remark.liked_record WHERE biz_id=999001"'
# 期望：0（取消赞 = 删行，表里没有 liked 列）
```

**判据**：三次调用后 `liked_record` 无残留、`XLEN` 归零、计数与关系一致。任一步不符 → 停在这条查，别往下做。

---

## 2. 坏记录：进死信 + 不阻塞新消息（A8 / A9 / A14）

**目的**：P2 的核心安全属性 —— 坏记录必须「保住原文 + 可定位 + 不卡住后面的变更」，而不是被 ACK 丢掉。

Stream 的字段是六个：`userId / bizType / bizId / liked / likedTimes / version`。`liked` 不是 `"1"`/`"0"` 就抛 `liked非法`（`LikeWriteBehindTask:307-309`），所以手工灌一条 `liked=2` 即可复现。

```bash
# ① 先灌一条正常记录（放后面，用来证明「不被阻塞」）
ssh $RHOST "$R XADD remark:like:changes '*' userId 9999 bizType QA bizId 999002 liked 1 likedTimes 7 version 9990000001"

# ② 再灌一条坏记录
BAD_ID=$(ssh $RHOST "$R XADD remark:like:changes '*' userId 9999 bizType QA bizId 999003 liked 2 likedTimes 8 version 9990000002" | tr -d '\r')
echo "坏记录 streamId = $BAD_ID"
```

等 2–3 秒（flush 间隔 1000ms），然后查三件事：

```bash
# a) 死信表里有它，且 reason 可定位
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" --table -e "SELECT stream_id, fail_reason, LEFT(payload,120) AS payload_head FROM tj_remark.liked_change_deadletter ORDER BY id DESC LIMIT 3"'
# 期望：stream_id = $BAD_ID，fail_reason 含 "liked非法"，payload 里有原始键值对

# b) 坏记录已被 XDEL（死信写成功才允许 XACK+XDEL）
ssh $RHOST "$R XLEN remark:like:changes; $R XPENDING remark:like:changes remark-like-db-writer"
# 期望：XLEN 0（或只剩余量）、pending 0

# c) 正常记录已落库 —— 这是「不阻塞」的证据
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" -N -e "SELECT COUNT(*) FROM tj_remark.liked_record WHERE biz_id=999002"'
# 期望：1
```

**判据（三条缺一不可）**：
1. 死信表有一行、`fail_reason` 指明原因、`payload` 保住原文；
2. Stream 里那条坏记录被清掉且 PEL 为空 —— **注意这与「不 ACK」不矛盾**：不 ACK 的前提是**死信写入失败**（`saveDeadLetter` 返回 false）；死信写成功就该正常收尾；
3. 同一轮里的正常记录照常落库。**如果只做了 a 而 c 失败，说明坏记录把管线堵住了** —— 这正是 A14 要抓的退化。

**可选（更强）**：把死信表临时改名，制造「死信写入失败」，验证坏记录**留在 PEL 不被 ACK**、日志出现 `ALERT 点赞死信写入失败`，且后续正常消息仍然照常处理（R1 的「每轮同时读一批 PEL + 一批新消息」）。改完记得改回表名。

```bash
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" -e "RENAME TABLE tj_remark.liked_change_deadletter TO tj_remark.liked_change_deadletter_bak"'
# ... 灌坏记录 + 正常记录，观察日志与 PEL ...
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" -e "RENAME TABLE tj_remark.liked_change_deadletter_bak TO tj_remark.liked_change_deadletter"'
# 改回后：逾期重试应自动把滞留的坏记录补写进死信（退避基 1s、上限 60s）
```

---

## 3. 未 READY → 503（返回码 -1）

**目的**：`-1` 必须映射成 503，且**绝不能**被当成 0（静默成功）。

```bash
# ① 手工把标记置成 BUILDING
ssh $RHOST "$R SET remark:like:cache:ready BUILDING"

# ② 三个入口都要拒
curl -s -o /dev/null -w "POST    → %{http_code}\n" -X POST "http://$WINIP:8091/likes" \
  -H "Content-Type: application/json" -H "user-info: 9999" \
  -d '{"bizId":999004,"bizType":"QA","liked":true}'
curl -s -o /dev/null -w "GET/status → %{http_code}\n" "http://$WINIP:8091/likes/status?bizType=QA&bizIds=999004" -H "user-info: 9999"
curl -s -o /dev/null -w "GET/times  → %{http_code}\n" "http://$WINIP:8091/likes/times?bizType=QA&bizIds=999004" -H "user-info: 9999"
# 期望：三个都是 503

# ③ 关系没有被写进去（-1 不能有副作用）
ssh $RHOST "$R SISMEMBER remark:like:user:QA:9999 999004"
# 期望：0

# ④ 恢复（务必执行，否则服务一直 503）
ssh $RHOST "$R SET remark:like:cache:ready READY"
curl -s -o /dev/null -w "恢复后 → %{http_code}\n" -X POST "http://$WINIP:8091/likes" \
  -H "Content-Type: application/json" -H "user-info: 9999" \
  -d '{"bizId":999004,"bizType":"QA","liked":true}'
# 期望：200
```

⚠️ **这一条最容易留下后遗症** —— 如果你中途离开，`READY` 就停在 `BUILDING`，之后所有点赞都 503。第 ④ 步永远要跑。

**Redis 不可用 → 503（另一条路径）**：把隧道里 6379 断开（或 `$R CLIENT KILL` 之类），再打上面三个入口，期望同样 503 而不是 500。这条验证的是 P3 把 catch 放宽到 `DataAccessException` 的效果 —— 尤其是 **Lettuce 命令超时**（`RedisCommandTimeoutException`，它**不是** `RedisConnectionFailureException` 的子类，P3 之前会漏成 500）。

---

## 4. bizType 非法 → 400

```bash
# POST：@Pattern(regexp="QA|NOTE") 挡
curl -s -o /dev/null -w "POST 非法 → %{http_code}\n" -X POST "http://$WINIP:8091/likes" \
  -H "Content-Type: application/json" -H "user-info: 9999" \
  -d '{"bizId":1,"bizType":"XXX","liked":true}'
# 期望：400

# GET：validateBizType 挡（P2 已把这里的 ConstraintViolationException 改成 BadRequestException）
curl -s -o /dev/null -w "GET 非法 → %{http_code}\n" "http://$WINIP:8091/likes/status?bizType=XXX" -H "user-info: 9999"
# 期望：400（修复前是 200）

# 顺带验参数校验也走 400
curl -s -o /dev/null -w "GET 超长 bizIds → %{http_code}\n" "http://$WINIP:8091/likes/status?bizType=QA&bizIds=$(seq -s, 1 501)" -H "user-info: 9999"
# 期望：400（@Size(max=500)）
```

**NOTE 的现状与决策点**：

```bash
curl -s -o /dev/null -w "NOTE → %{http_code}\n" -X POST "http://$WINIP:8091/likes" \
  -H "Content-Type: application/json" -H "user-info: 9999" \
  -d '{"bizId":999005,"bizType":"NOTE","liked":true}'
# 现状：200（能点）—— 因为 P2 的 D4 明确「本批不改 NOTE 入口」
```

**NOTE 的处置**：已决定**保持入口开放**（2026-09-28），依据与后续接线点见第 7 节。现在 `POST /likes` 带 NOTE 返回 200 属预期行为，不需要改成 400。

---

## 5. 带未落库记录 / pending 重启 → 三处一致

**目的**：P2 最重要的验收项 —— 重启后 Redis 关系、计数、MySQL 三者必须收敛到一致，且过程中**不能丢**。

### 5.1 造「重启时 Stream 里还有东西」

在 flush 窗口（1000ms）内灌数据并立刻停服务：

```bash
for i in $(seq 1 5); do
  ssh $RHOST "$R XADD remark:like:changes '*' userId 9999 bizType QA bizId 99901$i liked 1 likedTimes $i version 99910000$i" >/dev/null
done
ssh $RHOST "$R XLEN remark:like:changes"          # 期望 5
# ↓ 立刻在 IDEA 里 Stop 服务（或 taskkill 掉 8091 的那个 java 进程）
```

> 手工 `XADD` 的副作用要知道：它**只写 Stream，不写 Set/ZSet**（那是 Lua 干的）。所以接下来重启会把这些变更落进 MySQL，但 Redis 的 Set/ZSet 里没有对应成员 → 这正好顺便验证了「**落库不回写 Redis**」这条已知特性，别把它误判成 bug。要做「三处一致」的严格比对，请用 5.2 的方式。

### 5.2 严格版：比对「Redis 权威状态」与「MySQL 落库状态」

用**真实接口**制造变更（这样 Set/ZSet/Stream 三者都有），但在落库前打断：

```bash
for i in $(seq 1 5); do
  curl -s -o /dev/null -X POST "http://$WINIP:8091/likes" -H "Content-Type: application/json" \
    -H "user-info: 9999" -d "{\"bizId\":99920$i,\"bizType\":\"QA\",\"liked\":true}"
done
# 立刻停服务（1 秒内）
```

重启后（`LikeCacheInitializer` 会先排空再重建），比对两处：

```bash
echo "--- Redis 侧 ---"
ssh $RHOST "$R SMEMBERS remark:like:user:QA:9999 | sort"
ssh $RHOST "$R ZRANGE remark:like:count:QA 0 -1 WITHSCORES"
echo "--- MySQL 侧 ---"
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" -N -e "SELECT biz_id FROM tj_remark.liked_record WHERE user_id=9999 AND biz_type=\"QA\" ORDER BY biz_id"'
echo "--- 落库后 Stream 应清空 ---"
ssh $RHOST "$R XLEN remark:like:changes; $R XPENDING remark:like:changes remark-like-db-writer"
```

**判据**：
- Set 与 `liked_record` 的 bizId 集合**完全一致**（`comm -3` 逐行比）；
- ZSet 里每个成员的分值 == 该 bizId 在 `liked_record` 里的行数；
- `XLEN` 归零、`pending` 归零；
- 服务启动日志里出现排空完成、`READY` 最终回到 `READY`。

**顺带覆盖 A-1（重建期间不接受写入）**：重启后**立刻**打一次点赞请求（趁还在 `BUILDING`），期望 503；等 `READY` 后再打，期望 200。这就是「停写排空 → 重建 → 恢复」的完整证据。

---

## 6. 多实例 / 并发重建（可选，但很有说服力）

**目的**：Redisson 锁保证「只重建一次」，多实例同时启动不能各建各的。

- 同时起两个 `tj-remark` 实例（改一个的 `server.port`，比如 `-Dserver.port=8092`）；
- 观察日志：只有一个实例拿到 `lock:remark:like:initialize` 并执行重建，另一个直接跳过；
- 两个实例都起来后，两边打点赞请求，计数**不能翻倍**。

风险：两个实例会共用同一个 consumer group 消费 Stream —— 这正是生产形态，值得试；但测试期间别开太久。

---

## 7. NOTE 入口：保持开放（决策记录）

**决定（2026-09-28）：不关 NOTE 入口，后续接笔记功能时一起完善。**

依据（实测）：

| 检查 | 结果 |
|---|---|
| `tj_learning` 里 note 相关表 | **0** |
| 本地 `tj-learning` 代码里 note 相关 | **0 处** |
| `liked_record` 里 `biz_type=NOTE` 的行 | 0（只有 QA 一行） |

**为什么开着是安全的**：笔记功能整体还不存在（无表、无代码、前端无入口）⇒ 真实用户**点不到** NOTE 赞 ⇒ 不会产生 NOTE 数据。它只是为后续预留的入口，所以现在 `POST /likes` 带 NOTE 返回 200 属**预期行为**，不需要复验为 400。

**后面接 NOTE 时补什么**（现在已通的部分都不用动）：

- **已经能用**：Lua（bizType 参数化）、`toChange` 里的 `LikeBizType.valueOf(bizType)` 校验（NOTE 在枚举里）、`liked_record.biz_type`（varchar，不区分类型）、MQ 的 routing key `NOTE.times.changed` + 兜底队列
- **要补的**：笔记业务本身（`tj-learning` 的 note 表 / PO / 接口）+ NOTE 的消息消费者（接上 `remark.note.liked.times.queue`，照 `learning.qa.liked.times.queue` 的现成写法）

**兜底队列的意义正在于此**：`remark.note.liked.times.queue`（`LikeRabbitConfig:23`，durable）保证「NOTE 已上线但消费者还没接」的过渡期里，消息有地方等着，**不会因为路由不到而卡住 Stream**（`LikeRabbitConfig:20` 的注释即此意）。

**唯一观察项**：过渡期若真有 NOTE 流量，该队列会持续堆积（durable + 无消费者）。第二批把「兜底队列积压」纳入监控指标即可，**现在不需要改任何代码**。

---

## 8. 收尾清理

```bash
# Redis：清掉测试用户 9999 的全部痕迹
ssh $RHOST "$R DEL remark:like:user:QA:9999 remark:like:user:NOTE:9999; \
  $R ZREM remark:like:count:QA 999001 999002 999003 999004 999005 999011 999012 999013 999014 999015 999021 999022 999023; \
  $R ZREM remark:like:count:NOTE 999005; \
  $R HDEL remark:like:version:QA 999001 999002 999003 999004 999005 999011 999012 999013 999014 999015 999021 999022 999023; \
  $R HDEL remark:like:version:NOTE 999005"
# ⚠️ 不要 `DEL remark:like:version:QA`：version 是整 Hash，DEL 会连真实业务（如 user_id=2 的 bizId）的版本号一起清掉。
#    版本号是下游拒绝旧消息的单调基线，清掉它可能导致旧消息被当新消息处理 —— 必须逐个 HDEL。

# MySQL：删测试数据
ssh $RHOST 'PW=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD); docker exec -i mysql mysql -uroot -p"$PW" -e "
DELETE FROM tj_remark.liked_record WHERE user_id=9999;
DELETE FROM tj_remark.liked_change_deadletter WHERE payload LIKE \"%99900%\" OR payload LIKE \"%99920%\";
"'
```

清理后**回到 0.3 / 0.4 的基线**再确认一次：`liked_record` 应只剩 `user_id=2` 那一行、死信表 0 行、`XLEN=0`、`READY=READY`。

---

## 9. 验收结果记录表

每批交付都要留「实际改动 / 自动化测试结果 / 未完成的真实环境验证」，照下表填：

| # | 验收项 | 判据 | 结果 | 证据（命令输出/日志片段） |
|---|---|---|---|---|
| 1 | 正常链路 + 幂等 | 计数不重复、XLEN 归零、取消赞删行 | ✅ | 首次 `POST → 1`；重复请求 `ZSCORE` 未增长、`XLEN=0`；取消后 `liked_record` 删行、剩余 0 行 |
| 2 | 坏记录进死信 | 死信表有行 + reason + payload | ✅ | `stream_id=1790578771374-0`、`fail_reason=liked非法`、`payload={"userId":"9999","bizType":"QA","bizId":"999003","liked":"2","likedTimes":"8","version":"9990000002"}`（六字段完整） |
| 3 | 坏记录不阻塞 | 同轮正常记录照常落库 | ✅ | 同批正常记录 999002 正常落库；最终 `XLEN=0 / PENDING=0` |
| 4 | 未 READY → 503 | 三入口全 503、无副作用 | ✅ | `POST` / `GET status` / `GET times` 全 503；`SISMEMBER=0`、`XLEN=0`（`-1` 分支未写任何数据）；恢复 READY 后 200 |
| 5 | Redis 不可用 → 503 | 超时也走 503（非 500） | ⚠ 未演练（单测覆盖） | 真实演练须只让 remark 受影响，会波及其余 11 个在跑的服务 → 风险大于收益。超时→503 已由 `LikeCacheServiceTest` 用真实 `LettuceExceptionConverter` 覆盖（4 处 catch），另有 `programmingErrorIsNotTranslatedTo503` 守住反向断言 |
| 6 | bizType 非法 → 400 | POST / GET 均 400 | ✅ | `POST bizType=XXX → 400`；`GET status?bizType=XXX → 400`；`GET times?bizType=XXX → 400`；`GET bizType=QA → 200` |
| 7 | 带未落库记录重启 | Set == liked_record、ZSet == 行数、XLEN/pending 归零 | ✅ | 两轮：①5 条 owner=`remark-like-writer` 的 PEL 被自动接管落库；②删 `READY` 后灌 3 条（2 条在 PEL、1 条未投递）→ 重启走排空+重建。最终 `XLEN=0 / PENDING=0`、`comm -3` 零差异、MySQL 3→8→11 行 |
| 8 | 重建窗口拒写 | BUILDING 期 503、READY 后 200 | ✅ | 连续 40 次探测（20 秒）全 503，无一漏过；日志 `15:07:16 c.t.remark.task.LikeCacheInitializer : 点赞 Redis 缓存初始化完成`；同批 40 条 `LikeServiceUnavailableException` 与探测一一对应 |
| 9 | 并发重建（可选） | 只重建一次、计数不翻倍 | — 未做 | 需双实例；编码侧已由 `run()` 的 Redisson `INITIALIZE_LOCK` + `WORKER_LOCK` 串行化保证，第一批不做实测 |

**结论：8 项通过、第 5 项以单测覆盖代替（已说明理由）、第 9 项未做（可选项）。**
**⇒ 第一批（P1–P3）验收完成**，可以进第二批（监控 / 对账 / 降级 / 积压水位）。
（NOTE 入口保持开放，见第 7 节，不在验收范围。）

### 本轮验收新增的发现（都进第二批，不在第一批修）

| 级别 | 发现 | 根因 | 建议 |
|---|---|---|---|
| 🟡 | `READY` 丢失且不重启 → 点赞接口**永久 503**，无自动恢复路径 | `@Scheduled` 的 `retryFailedSnapshots()`（`LikeCacheInitializer:124-143`）职责只是 **MQ 快照补发重试**，且 `READY != READY` 时主动 `return`；唯一的重建入口 `run()` 是 `ApplicationRunner`，只在启动时跑 | **做告警不做自动重建**：非 READY 持续 > N 秒即告警、人工重启。理由：自动重建要在多实例下抢锁 + 退避，复杂度与风险都超出收益；503 是明确失败信号，不会静默错数据 |
| 🔵 | `@Size` 校验失败时**直连返回 200**（网关侧业务码仍是 400） | `@Size(max=500)` 触发的 `ConstraintViolationException` 走 `CommonExceptionAdvice:70-77`，那里传的是 `HttpStatus.OK`。P2 只修好了 `bizType` 路径（改成抛 `BadRequestException`） | 全局行为（影响所有服务不只 remark），影响面仅"直连调试时状态码不准"，**不改** |
| 🔵 | 其他 consumer 名遗留的 pending **不会被定时任务接管** | `claimOrphanPending()` 只被 `drainOneStreamBatch()` 调用，而后者只由初始化排空流程调用；定时路径的 `readOneStreamBatch` 只读**自己名字**的 PEL（`Consumer.from(group, WORKER_NAME)`） | **真实崩溃场景不可达**：`WORKER_NAME` 是固定常量 `remark-like-writer`，重启后同名 consumer 读自己的 PEL 即可恢复。仅当未来改成随机 consumer 名 / 多实例异构命名时才需常驻 claim —— 记为设计边界 |

---

## 附：本次验收已知的边界（不是缺陷，是设计取舍）

1. **落库不回写 Redis** —— 手工 `XADD` 或崩溃前的变更落进 MySQL 后，Redis 的 Set/ZSet 不会跟着更新；一致性靠「重建时从 MySQL 全量重建」。所以第 5 节的严格比对必须用**真实接口**造数据。
2. **排空改判据后，「已 ACK 未 XDEL」的残留会被放行**（`LikeWriteBehindTask:133-137` 用 `<` 而非 `!=`）。残留会占 `XLEN` 但不影响正确性；清理手段留待第二批的告警一起做（`XLEN>0 且 pending==0` 持续 → 人工 `XTRIM MINID`，前提：本 Stream 只有一个 group）。
3. **`deadLetterBackoffs` 无上限**、**`retrySnapshots` 是内存 Map（重启丢）** —— 两条都并入第二批。
4. **NOTE 入口保持开放** —— 见第 7 节；这是决策不是缺陷，后续接笔记功能时一并完成。
5. **验收异步链路时，判据必须等到收敛** —— 本次连踩三次「查早了就下结论」（`XLEN`/`PENDING` 是**中间态**：落库 → 发 MQ → `XACK` → `XDEL` 之间天然有几秒窗口；隧道抖动和数据源**惰性初始化**（`HikariPool-1 - Starting...` 实测比服务启动晚 1 分钟）会把窗口拉到十几秒）。做法：每 2 秒轮询、连续两次相同再判定，或只对最终收敛值下结论；看到中间态先查 `logs/<服务>/spring.log` 时间线，别先怀疑代码。
6. **`DEBUG` 证据要挑对工具** —— 想证明「重建确实发生过」，往 Redis 塞一个 MySQL 里不存在的假成员再触发重建（本次用过，对**重建**有效）；但对 `retryFailedSnapshots()` 无效，因为那个 `@Scheduled` 根本不做重建。**先读实现确认任务职责，再设计验证手段。**
