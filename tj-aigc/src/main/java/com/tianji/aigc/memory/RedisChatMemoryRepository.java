package com.tianji.aigc.memory;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.Assert;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 基于Redis实现的ChatMemoryRepository
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    // 默认redis中key的前缀
    public static final String DEFAULT_PREFIX = "CHAT:";
    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV do
                redis.call('RPUSH', KEYS[1], ARGV[i])
            end
            return 1
            """, Long.class);

    private final String prefix;

    // 注入spring redis模板，进行redis的操作
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public RedisChatMemoryRepository() {
        this.prefix = DEFAULT_PREFIX;
    }

    public RedisChatMemoryRepository(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public List<String> findConversationIds() {
        var ids = new LinkedHashSet<String>();
        // SCAN 可能重复返回同一 key；只去掉开头的命名空间，保留 ID 内的同名片段。
        try (var cursor = stringRedisTemplate.scan(ScanOptions.scanOptions().match(prefix + "*").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (key.startsWith(prefix)) ids.add(key.substring(prefix.length()));
            }
        }
        return List.copyOf(ids);
    }


    @Override
    public List<Message> findByConversationId(String conversationId) {
        var jsonMessages = stringRedisTemplate.opsForList().range(getKey(conversationId), 0, -1);
        if (jsonMessages == null || jsonMessages.isEmpty()) {
            return List.of();
        }
        return jsonMessages.stream()
                .map(MessageUtil::toMessage)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String redisKey = getKey(conversationId);
        Assert.notNull(messages, "消息列表不能为null");
        // 在修改 Redis 前完成全部序列化；一次脚本替换整个窗口，空窗口即删除。
        Object[] serialized = messages.stream().map(MessageUtil::toJson).toArray();
        stringRedisTemplate.execute(REPLACE, List.of(redisKey), serialized);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        var redisKey = this.getKey(conversationId);
        this.stringRedisTemplate.delete(redisKey);
    }

    private String getKey(String conversationId) {
        Assert.hasText(conversationId, "对话ID不能为空");
        return prefix + conversationId;
    }
}
