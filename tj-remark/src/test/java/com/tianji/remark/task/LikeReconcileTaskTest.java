package com.tianji.remark.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.utils.LikeCacheKeys;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

class LikeReconcileTaskTest {
    @Test
    void reportsRedisOnlyMemberWithoutDeletingIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        LikedRecordMapper mapper = mock(LikedRecordMapper.class);
        StreamInfo.XInfoStream info = mock(StreamInfo.XInfoStream.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(0L);
        when(streams.info(LikeCacheKeys.CHANGE_STREAM)).thenReturn(info);
        when(info.lastGeneratedId()).thenReturn("1000-0");
        when(sets.members(LikeCacheKeys.userBizSet("QA", 2L))).thenReturn(Set.of("888888"));
        when(zsets.score(LikeCacheKeys.countSortedSet("QA"), "888888")).thenReturn(1.0);
        when(mapper.selectList(any())).thenReturn(
                java.util.List.of(new LikedRecord().setUserId(2L).setBizType("QA")),
                Collections.emptyList());
        when(mapper.selectCount(any())).thenReturn(0L);
        LikeReconcileTask task = new LikeReconcileTask(redis, mapper);
        Logger logger = (Logger) LoggerFactory.getLogger(LikeReconcileTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.reconcile();
            assertTrue(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT[reconcile]") &&
                            e.getFormattedMessage().contains("888888")));
            assertTrue(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("点赞计数差异 bizType=QA bizId=888888")));
            verify(redis, never()).delete(anyString());
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void consistentSampleProducesNoAlert() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        LikedRecordMapper mapper = mock(LikedRecordMapper.class);
        StreamInfo.XInfoStream info = mock(StreamInfo.XInfoStream.class);
        LikedRecord record = new LikedRecord().setUserId(2L).setBizType("QA").setBizId(888888L);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(0L);
        when(streams.info(LikeCacheKeys.CHANGE_STREAM)).thenReturn(info);
        when(info.lastGeneratedId()).thenReturn("1000-0");
        when(sets.members(LikeCacheKeys.userBizSet("QA", 2L))).thenReturn(Set.of("888888"));
        when(zsets.score(LikeCacheKeys.countSortedSet("QA"), "888888")).thenReturn(1.0);
        when(mapper.selectList(any())).thenReturn(java.util.List.of(record));
        when(mapper.selectCount(any())).thenReturn(1L);
        LikeReconcileTask task = new LikeReconcileTask(redis, mapper);
        Logger logger = (Logger) LoggerFactory.getLogger(LikeReconcileTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.reconcile();
            assertFalse(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT[reconcile]")));
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void newStreamWriteDuringComparisonSuppressesIntermediateDifference() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        LikedRecordMapper mapper = mock(LikedRecordMapper.class);
        StreamInfo.XInfoStream info = mock(StreamInfo.XInfoStream.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(0L);
        when(streams.info(LikeCacheKeys.CHANGE_STREAM)).thenReturn(info);
        when(info.lastGeneratedId()).thenReturn("1000-0", "1001-0");
        when(mapper.selectList(any())).thenReturn(
                java.util.List.of(new LikedRecord().setUserId(2L).setBizType("QA")),
                Collections.emptyList());
        when(sets.members(LikeCacheKeys.userBizSet("QA", 2L))).thenReturn(Set.of("888888"));
        when(mapper.selectCount(any())).thenReturn(0L);
        LikeReconcileTask task = new LikeReconcileTask(redis, mapper);
        Logger logger = (Logger) LoggerFactory.getLogger(LikeReconcileTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.reconcile();
            assertFalse(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT[reconcile]")));
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void skipsComparisonWhileStreamHasUnpersistedChanges() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        LikedRecordMapper mapper = mock(LikedRecordMapper.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(1L);
        LikeReconcileTask task = new LikeReconcileTask(redis, mapper);
        Logger logger = (Logger) LoggerFactory.getLogger(LikeReconcileTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.reconcile();
            assertFalse(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT[reconcile]")));
        } finally {
            logger.detachAppender(events);
        }
    }
}
