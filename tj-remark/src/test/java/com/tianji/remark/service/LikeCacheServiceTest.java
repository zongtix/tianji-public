package com.tianji.remark.service;

import com.tianji.remark.utils.LikeCacheKeys;
import com.tianji.remark.task.LikeMetricsTask;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceExceptionConverter;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import io.lettuce.core.RedisCommandTimeoutException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.tianji.remark.exception.LikeServiceUnavailableException;

class LikeCacheServiceTest {
    @Test
    void notReadyCodeRejectsWriteAndReadyKeyIsPassedToLua() {
        StringRedisTemplate redis = redisWithEmptyStream();
        when(redis.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString())).thenReturn(-1L);

        LikeServiceUnavailableException error = assertThrows(LikeServiceUnavailableException.class,
                () -> newService(redis).change(1L, "QA", 2L, true));

        assertEquals(503, error.getStatus());
        verify(redis).execute(any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of(
                        LikeCacheKeys.userBizSet("QA", 1L),
                        LikeCacheKeys.countSortedSet("QA"),
                        LikeCacheKeys.versionHash("QA"),
                        LikeCacheKeys.CHANGE_STREAM,
                        LikeCacheKeys.READY_KEY)), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unchangedAndChangedCodesAreBothSuccessful() {
        StringRedisTemplate redis = redisWithEmptyStream();
        when(redis.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L, 1L);
        LikeCacheService service = newService(redis);

        assertDoesNotThrow(() -> service.change(1L, "QA", 2L, true));
        assertDoesNotThrow(() -> service.change(1L, "QA", 2L, true));
    }

    @Test
    void redisConnectionFailureReturnsServiceUnavailable() {
        StringRedisTemplate redis = redisWithEmptyStream();
        when(redis.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertEquals(503, assertThrows(LikeServiceUnavailableException.class,
                () -> newService(redis).change(1L, "QA", 2L, true)).getStatus());
    }

    @Test
    void translatedCommandTimeoutOnChangeReturns503() {
        StringRedisTemplate redis = redisWithEmptyStream();
        QueryTimeoutException timeout = translatedTimeout();
        when(redis.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString())).thenThrow(timeout);

        LikeServiceUnavailableException error = assertThrows(LikeServiceUnavailableException.class,
                () -> newService(redis).change(1L, "QA", 2L, true));
        assertEquals(503, error.getStatus());
        assertSame(timeout, error.getCause());
    }

    @Test
    void translatedCommandTimeoutOnLikedIdsQueryReturns503() {
        StringRedisTemplate redis = readyRedis();
        QueryTimeoutException timeout = translatedTimeout();
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(timeout);

        LikeServiceUnavailableException error = assertThrows(LikeServiceUnavailableException.class,
                () -> newService(redis).queryMyLikedBizIds(1L, "QA", List.of(2L)));
        assertEquals(503, error.getStatus());
        assertSame(timeout, error.getCause());
    }

    @Test
    void translatedCommandTimeoutOnTimesQueryReturns503() {
        StringRedisTemplate redis = readyRedis();
        QueryTimeoutException timeout = translatedTimeout();
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(timeout);

        LikeServiceUnavailableException error = assertThrows(LikeServiceUnavailableException.class,
                () -> newService(redis).queryLikedTimes("QA", List.of(2L)));
        assertEquals(503, error.getStatus());
        assertSame(timeout, error.getCause());
    }

    @Test
    void translatedCommandTimeoutOnReadyCheckReturns503() {
        StringRedisTemplate redis = redisWithEmptyStream();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        QueryTimeoutException timeout = translatedTimeout();
        when(values.get(LikeCacheKeys.READY_KEY)).thenThrow(timeout);

        LikeServiceUnavailableException error = assertThrows(LikeServiceUnavailableException.class,
                () -> newService(redis).queryLikedTimes("QA", List.of(2L)));
        assertEquals(503, error.getStatus());
        assertSame(timeout, error.getCause());
    }

    @Test
    void connectionFailureOnBothQueriesStillReturns503() {
        StringRedisTemplate redis = readyRedis();
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));
        LikeCacheService service = newService(redis);

        assertEquals(503, assertThrows(LikeServiceUnavailableException.class,
                () -> service.queryMyLikedBizIds(1L, "QA", List.of(2L))).getStatus());
        assertEquals(503, assertThrows(LikeServiceUnavailableException.class,
                () -> service.queryLikedTimes("QA", List.of(2L))).getStatus());
    }

    @Test
    void programmingErrorIsNotTranslatedTo503() {
        StringRedisTemplate redis = redisWithEmptyStream();
        when(redis.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new NullPointerException("bug"));

        assertThrows(NullPointerException.class,
                () -> newService(redis).change(1L, "QA", 2L, true));
    }

    @Test
    void backlogRejectsWritesUntilBelowResumeWatermarkWithoutRunningLua() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        LikeMetricsTask metrics = mock(LikeMetricsTask.class);
        when(metrics.rejectingWrites()).thenReturn(true, true, false);
        when(redis.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1L);
        LikeCacheService service = new LikeCacheService(redis, metrics, mock(LikeTargetService.class));

        assertEquals(503, assertThrows(LikeServiceUnavailableException.class,
                () -> service.change(1L, "QA", 2L, true)).getStatus());
        assertEquals(503, assertThrows(LikeServiceUnavailableException.class,
                () -> service.change(1L, "QA", 2L, true)).getStatus());
        assertDoesNotThrow(() -> service.change(1L, "QA", 2L, true));

        verify(redis, times(1)).execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString());
        verify(redis, never()).opsForStream();
    }

    @Test
    void programmingErrorOnReadIsNotTranslatedTo503() {
        StringRedisTemplate redis = readyRedis();
        IllegalStateException bug = new IllegalStateException("wrong type in caller");
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(bug);

        assertSame(bug, assertThrows(IllegalStateException.class,
                () -> newService(redis).queryLikedTimes("QA", List.of(2L))));
    }

    private StringRedisTemplate readyRedis() {
        StringRedisTemplate redis = redisWithEmptyStream();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        return redis;
    }

    private LikeCacheService newService(StringRedisTemplate redis) {
        LikeMetricsTask metrics = mock(LikeMetricsTask.class);
        when(metrics.rejectingWrites()).thenReturn(false);
        return new LikeCacheService(redis, metrics, mock(LikeTargetService.class));
    }

    private StringRedisTemplate redisWithEmptyStream() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(0L);
        return redis;
    }

    private QueryTimeoutException translatedTimeout() {
        DataAccessException translated = new LettuceExceptionConverter()
                .convert(new RedisCommandTimeoutException("timed out"));
        assertTrue(translated instanceof QueryTimeoutException);
        return (QueryTimeoutException) translated;
    }
}
