-- 一次脚本调用批量读取 ZSet 分数；未出现过的对象按 0 个赞返回。
-- KEYS[1] 是某业务类型的 ZSet，ARGV 是当前页对象 ID；ZSCORE 即即时总数。
-- 返回 [id1,count1,id2,count2,...]，Java 按步长 2 转 Map<Long,Integer>，成员缺失明确返回 0。
local result = {}
for i = 1, #ARGV do
    local score = redis.call('ZSCORE', KEYS[1], ARGV[i])
    local count = '0'
    if score then
        count = tostring(math.floor(tonumber(score)))
    end
    table.insert(result, ARGV[i])
    table.insert(result, count)
end
return result
