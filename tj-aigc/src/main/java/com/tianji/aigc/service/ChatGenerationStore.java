package com.tianji.aigc.service;

import com.tianji.common.exceptions.BadRequestException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 多个 AIGC 实例共享生成状态；所有脚本只操作一个会话键。 */
@Component
public class ChatGenerationStore {
    private static final String KEY_PREFIX = "aigc:chat:generating:";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            if redis.call('exists', KEYS[1]) == 1 then return 0 end
            redis.call('hset', KEYS[1], 'requestId', ARGV[1])
            redis.call('hset', KEYS[1], 'state', 'running')
            redis.call('pexpire', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('hget', KEYS[1], 'requestId') ~= ARGV[1]
                or redis.call('hget', KEYS[1], 'state') ~= 'running' then return 0 end
            return redis.call('pexpire', KEYS[1], ARGV[2])
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('hget', KEYS[1], 'requestId') == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> STOP = new DefaultRedisScript<>("""
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            if ARGV[1] ~= '' and redis.call('hget', KEYS[1], 'requestId') ~= ARGV[1] then return 0 end
            if redis.call('hget', KEYS[1], 'state') == 'completed' then return 0 end
            redis.call('hset', KEYS[1], 'state', 'stopped')
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
            if redis.call('hget', KEYS[1], 'requestId') ~= ARGV[1]
                or redis.call('hget', KEYS[1], 'state') ~= 'running' then return 0 end
            redis.call('hset', KEYS[1], 'state', 'completed')
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> IS_CURRENT = new DefaultRedisScript<>("""
            if redis.call('hget', KEYS[1], 'requestId') == ARGV[1] then return 1 end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public ChatGenerationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Generation acquire(String sessionId) {
        String key = key(sessionId);
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Long acquired = redis.execute(ACQUIRE, List.of(key), requestId, Long.toString(LEASE.toMillis()));
        if (!Long.valueOf(1).equals(acquired)) {
            throw new BadRequestException("当前会话正在生成，请稍后再试");
        }
        return new Generation(key, requestId, started + LEASE.toNanos());
    }

    public boolean renew(Generation generation) {
        // 进程长时间暂停后不恢复已过期的本地租约，避免继续输出旧请求的数据。
        if (!generation.isValid()) return false;
        long started = System.nanoTime();
        Long renewed = redis.execute(RENEW, List.of(generation.key), generation.requestId,
                Long.toString(LEASE.toMillis()));
        if (!Long.valueOf(1).equals(renewed)) return false;
        generation.validUntil.set(started + LEASE.toNanos());
        return generation.isValid();
    }

    public boolean isCurrent(Generation generation) {
        if (!generation.isValid()) return false;
        return Long.valueOf(1).equals(redis.execute(IS_CURRENT, List.of(generation.key), generation.requestId));
    }

    public void release(Generation generation) {
        redis.execute(RELEASE, List.of(generation.key), generation.requestId);
    }

    /** 与停止在同一个 Redis 键上竞争，只有仍运行的请求可以封存完成状态。 */
    public boolean complete(Generation generation) {
        if (!generation.isValid()) return false;
        return Long.valueOf(1).equals(redis.execute(COMPLETE, List.of(generation.key), generation.requestId));
    }

    public void stop(String sessionId) {
        // 保留占位和原有 TTL，等执行实例取消模型流并释放，防止新旧流并行。
        redis.execute(STOP, List.of(key(sessionId)), "");
    }

    public boolean stop(String sessionId, String requestId) {
        if (!StringUtils.hasText(requestId) || requestId.length() != 36) return false;
        return Long.valueOf(1).equals(redis.execute(STOP, List.of(key(sessionId)), requestId));
    }

    private String key(String sessionId) {
        if (!StringUtils.hasText(sessionId)) throw new BadRequestException("会话ID不能为空");
        return KEY_PREFIX + sessionId;
    }

    public static final class Generation {
        private final String key;
        private final String requestId;
        private final AtomicLong validUntil;

        private Generation(String key, String requestId, long validUntil) {
            this.key = key;
            this.requestId = requestId;
            this.validUntil = new AtomicLong(validUntil);
        }

        public String requestId() {
            return requestId;
        }

        public boolean isValid() {
            return System.nanoTime() - validUntil.get() < 0;
        }
    }
}
