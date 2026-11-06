-- count/version 必须来自同一个时点，否则旧数量配新版本会阻止正确快照被应用。
-- KEYS: count ZSet, version Hash, READY marker. ARGV: bizId, 是否允许 BUILDING。
-- 由恢复补发调用：返回 [总数, 版本]，不是每个用户的点赞关系。
-- 允许 BUILDING 只用于初始化器持有锁、回填完成后的补发；普通补发须等 READY。
local state = redis.call('GET', KEYS[3])
if state ~= 'READY' and not (ARGV[2] == '1' and state == 'BUILDING') then
    return {}
end
local count = redis.call('ZSCORE', KEYS[1], ARGV[1]) or '0'
local version = redis.call('HGET', KEYS[2], ARGV[1])
-- 历史关系从 MySQL 回填后可能尚无版本，读取快照时建立时间基线供下游比较。
-- 读取 count 和建立/读取 version 在同一脚本中，不会出现旧 count 配较新 version 的交叉快照。
if not version then
    local now = redis.call('TIME')
    version = string.format('%.0f', tonumber(now[1]) * 1000000 + tonumber(now[2]))
    redis.call('HSET', KEYS[2], ARGV[1], version)
end
return {count, version}
