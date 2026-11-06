-- 以一次 Redis 脚本调用检查一组 id，避免状态接口对每个 id 各发一次请求。
-- KEYS[1] 是某类型某用户的 Set；ARGV 是待查询 ID 列表，返回其中存在于 Set 的 ID 子集。
-- Java 转为 Set<Long>，learning 用 contains 组装 ReplyVO.liked，不需要查询总数或用户资料。
local likedIds = {}
for i = 1, #ARGV do
    if redis.call('SISMEMBER', KEYS[1], ARGV[i]) == 1 then
        table.insert(likedIds, ARGV[i])
    end
end
return likedIds
