-- 只在点赞关系真正变化时修改总数并记录待落库事件。
-- Lua 在 Redis 内原子执行，避免并发请求把关系、数量和变更日志写成不一致状态。
-- KEYS: 1=user Set, 2=count ZSet, 3=version Hash, 4=change Stream, 5=READY marker.
-- ARGV: 1=bizId，2=userId，3=期望状态（1/0），4=bizType；位置与 LikeCacheService.change 对齐。
-- 返回 1 为有效变更，0 为幂等命中，-1 为未就绪，Java 将前两种视为成功。
-- 第 1 步：在脚本内检查共享状态，避免 Java 检查后恰好发生重建切换。
if redis.call('GET', KEYS[5]) ~= 'READY' then
    return -1
end
-- 第 2 步：Set 同一成员只保存一次；SADD/SREM 返回 1 代表关系发生变化，0 代表重复请求。
-- 脚本执行期间没有其他 Redis 请求穿插，同对象并发请求由 Redis 按到达顺序执行。
-- 这保证执行隔离，不表示脚本运行错误时 Redis 会像数据库事务那样自动回滚已执行命令。
local changed
if ARGV[3] == '1' then
    changed = redis.call('SADD', KEYS[1], ARGV[1])
else
    changed = redis.call('SREM', KEYS[1], ARGV[1])
end

if changed == 0 then
    return 0
end

-- 第 3 步：只有真实关系变化才增减对象总数；不能先改计数再判断重复，否则重试会重复计数。
local delta = 1
if ARGV[3] ~= '1' then
    delta = -1
end
local count = redis.call('ZINCRBY', KEYS[2], delta, ARGV[1])
if tonumber(count) <= 0 then
    -- 全部用户取消后删除排行榜成员，避免历史业务 id 永久占用 ZSet 空间。
    redis.call('ZREM', KEYS[2], ARGV[1])
    count = '0'
end
-- 用 Redis 服务器的微秒时间作为版本基线，避免缓存重建后版本直接从 1 开始。
-- 取 max(当前微秒时间, 上一版本+1)，在版本 Hash 保留时，同微秒操作或时钟回退也能维持递增。
local serverTime = redis.call('TIME')
local timeVersion = tonumber(serverTime[1]) * 1000000 + tonumber(serverTime[2])
local lastVersion = tonumber(redis.call('HGET', KEYS[3], ARGV[1]) or '0')
local version = string.format('%.0f', math.max(timeVersion, lastVersion + 1))
redis.call('HSET', KEYS[3], ARGV[1], version)

-- 已处理条目由消费端 XDEL 清理，故意不设 MAXLEN。
-- 不通过裁剪日志丢弃未落库的 pending；消费停摆时由 LikeMetricsTask 积压水位保护限制新增写入。
-- 第 4 步：写入完整目标状态、绝对总数和版本，异步任务据此落库和通知。
-- 不在请求线程等待 MySQL/MQ；成功回复建立在 Redis 接收日志上，持久性还依赖 Redis 部署的持久化策略。
redis.call('XADD', KEYS[4], '*',
    'userId', ARGV[2],
    'bizType', ARGV[4],
    'bizId', ARGV[1],
    'liked', ARGV[3],
    'likedTimes', count,
    'version', version)
return 1
