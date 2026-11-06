package com.tianji.remark.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.mq.LikeTimesPublisher;
import com.tianji.remark.utils.LikeCacheKeys;
import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.lettuce.LettuceExceptionConverter;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.script.RedisScript;
import com.tianji.remark.service.LikeTargetService;
import java.util.List;
import java.util.Set;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LikeCacheInitializerTest {
    @Test
    void retriesSnapshotSavedByPreviousProcess() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        LikeTimesPublisher publisher = mock(LikeTimesPublisher.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(hashes.entries("remark:like:publish:retry")).thenReturn(Map.of("QA:2", "old-token"));
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(List.of("1", "3"));
        LikeCacheInitializer initializer = new LikeCacheInitializer(mock(LikedRecordMapper.class),
                redis, mock(RedissonClient.class), mock(LikeWriteBehindTask.class), publisher, existingTargets());

        initializer.retryFailedSnapshots();

        verify(publisher).publish(org.mockito.ArgumentMatchers.argThat(snapshots ->
                snapshots.size() == 1 && snapshots.get(0).getBizId().equals(2L)
                        && snapshots.get(0).getLikedTimes().equals(1)
                        && snapshots.get(0).getVersion().equals(3L)));
    }

    private LikeTargetService existingTargets() {
        LikeTargetService targets = mock(LikeTargetService.class);
        when(targets.existingIds("QA", List.of(2L))).thenReturn(Set.of(2L));
        return targets;
    }

    @Test
    void cacheRebuildDuringRetryKeepsMarkerAndDoesNotPublishIncompleteSnapshot() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        LikeTimesPublisher publisher = mock(LikeTimesPublisher.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(hashes.entries(LikeCacheKeys.RETRY_SNAPSHOTS)).thenReturn(Map.of("QA:2", "retry"));
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(List.of());
        LikeCacheInitializer initializer = new LikeCacheInitializer(mock(LikedRecordMapper.class),
                redis, mock(RedissonClient.class), mock(LikeWriteBehindTask.class), publisher, existingTargets());

        initializer.retryFailedSnapshots();

        verify(publisher, never()).publish(anyList());
        verify(redis, never()).execute(any(RedisScript.class),
                eq(List.of(LikeCacheKeys.RETRY_SNAPSHOTS)), anyString(), anyString());
    }

    @Test
    void redisTimeoutWhileBuildingKeepsNotReadyAndEmitsAlert() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(LikeCacheKeys.INITIALIZE_LOCK)).thenReturn(lock);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("BUILDING");
        QueryTimeoutException timeout = (QueryTimeoutException) new LettuceExceptionConverter()
                .convert(new RedisCommandTimeoutException("timed out"));
        when(redis.execute(any(RedisCallback.class))).thenThrow(timeout);
        LikeCacheInitializer initializer = new LikeCacheInitializer(mock(LikedRecordMapper.class),
                redis, redisson, mock(LikeWriteBehindTask.class), mock(LikeTimesPublisher.class), existingTargets());
        Logger logger = (Logger) LoggerFactory.getLogger(LikeCacheInitializer.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            assertSame(timeout, assertThrows(QueryTimeoutException.class,
                    () -> initializer.run(mock(ApplicationArguments.class))));
            verify(values, never()).set(eq(LikeCacheKeys.READY_KEY), eq("READY"));
            assertTrue(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT 点赞缓存初始化读取 Redis 失败")));
        } finally {
            logger.detachAppender(events);
        }
    }
}
