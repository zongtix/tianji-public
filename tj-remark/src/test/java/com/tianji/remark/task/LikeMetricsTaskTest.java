package com.tianji.remark.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tianji.remark.utils.LikeCacheKeys;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Properties;
import java.util.Collections;
import java.util.stream.Stream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class LikeMetricsTaskTest {
    @Test
    void logsOncePerIntervalAndAlertsWhenReadyRemainsStale() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AmqpAdmin amqp = mock(AmqpAdmin.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(0L);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("BUILDING");
        when(amqp.getQueueProperties("remark.note.liked.times.queue")).thenReturn(new Properties());
        LikeMetricsTask task = new LikeMetricsTask(redis, amqp, mock(LikeWriteBehindTask.class));
        Logger logger = (Logger) LoggerFactory.getLogger(LikeMetricsTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.sampleAt(1000);
            task.sampleAt(2000);
            task.sampleAt(32000);
            assertEquals(2, events.list.stream()
                    .filter(e -> e.getFormattedMessage().startsWith("like.metrics ")).count());
            assertTrue(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT 点赞缓存非 READY")));
            verify(values, never()).set(anyString(), anyString());
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void pendingLagRepeatsAfterIntervalWhileNoteQueueAlertStaysDeduplicated() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AmqpAdmin amqp = mock(AmqpAdmin.class);
        PendingMessagesSummary backlog = mock(PendingMessagesSummary.class);
        PendingMessagesSummary empty = mock(PendingMessagesSummary.class);
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM))
                .thenReturn(1001L, 1001L, 1001L, 0L, 1001L);
        when(streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP))
                .thenReturn(backlog, backlog, backlog, empty, backlog);
        when(backlog.getTotalPendingMessages()).thenReturn(1001L);
        when(backlog.minMessageId()).thenReturn("1000-0");
        when(empty.getTotalPendingMessages()).thenReturn(0L);
        when(streams.groups(LikeCacheKeys.CHANGE_STREAM)).thenReturn(groups);
        when(groups.stream()).thenAnswer(invocation -> Stream.of(group));
        when(group.groupName()).thenReturn(LikeCacheKeys.CHANGE_GROUP);
        when(group.lastDeliveredId()).thenReturn("1000-0");
        when(streams.range(eq(LikeCacheKeys.CHANGE_STREAM), any(Range.class), any(Limit.class)))
                .thenReturn(Collections.emptyList());
        Properties queue = new Properties();
        queue.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 1001);
        when(amqp.getQueueProperties("remark.note.liked.times.queue")).thenReturn(queue);
        LikeMetricsTask task = new LikeMetricsTask(redis, amqp, mock(LikeWriteBehindTask.class));
        Logger logger = (Logger) LoggerFactory.getLogger(LikeMetricsTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.sampleAt(62000);
            assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().contains("ALERT 点赞积压超阈值")));
            assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().contains("ALERT 点赞落库延迟超阈值")));
            assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().contains("ALERT NOTE 点赞兜底队列积压")));
            long alerts = events.list.stream().filter(e -> e.getFormattedMessage().startsWith("ALERT ")).count();
            task.sampleAt(63000);
            assertEquals(alerts, events.list.stream()
                    .filter(e -> e.getFormattedMessage().startsWith("ALERT ")).count());
            task.sampleAt(122000);
            assertEquals(2, events.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("ALERT 点赞落库延迟超阈值")).count());
            task.sampleAt(123000);
            task.sampleAt(124000);
            assertEquals(3, events.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("ALERT 点赞落库延迟超阈值")).count());
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void acknowledgedResidualAlertsOnlyAfterStalePeriod() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AmqpAdmin amqp = mock(AmqpAdmin.class);
        PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(1L);
        when(streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP)).thenReturn(summary);
        when(summary.getTotalPendingMessages()).thenReturn(0L);
        when(streams.groups(LikeCacheKeys.CHANGE_STREAM)).thenReturn(groups);
        when(groups.stream()).thenAnswer(invocation -> Stream.of(group));
        when(group.groupName()).thenReturn(LikeCacheKeys.CHANGE_GROUP);
        when(group.lastDeliveredId()).thenReturn("2000-0");
        when(streams.range(eq(LikeCacheKeys.CHANGE_STREAM), any(Range.class), any(Limit.class)))
                .thenReturn(Collections.emptyList());
        Properties queue = new Properties();
        queue.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0);
        when(amqp.getQueueProperties("remark.note.liked.times.queue")).thenReturn(queue);
        LikeMetricsTask task = new LikeMetricsTask(redis, amqp, mock(LikeWriteBehindTask.class));
        Logger logger = (Logger) LoggerFactory.getLogger(LikeMetricsTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.sampleAt(1000);
            assertTrue(events.list.stream().noneMatch(e ->
                    e.getFormattedMessage().contains("Stream 存在已确认残留")));
            task.sampleAt(301001);
            assertTrue(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT 点赞 Stream 存在已确认残留")));
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void writeGateUsesHysteresisAndRechecksStaleSamples() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AmqpAdmin amqp = mock(AmqpAdmin.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("BUILDING");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(50001L, 45000L, 39000L, 0L, 0L);
        PendingMessagesSummary pending = mock(PendingMessagesSummary.class);
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);
        when(streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP)).thenReturn(pending);
        when(pending.getTotalPendingMessages()).thenReturn(0L);
        when(streams.groups(LikeCacheKeys.CHANGE_STREAM)).thenReturn(groups);
        when(groups.stream()).thenAnswer(invocation -> Stream.of(group));
        when(group.groupName()).thenReturn(LikeCacheKeys.CHANGE_GROUP);
        when(group.lastDeliveredId()).thenReturn("0-0");
        when(streams.range(eq(LikeCacheKeys.CHANGE_STREAM), any(Range.class), any(Limit.class)))
                .thenReturn(Collections.emptyList());
        Properties queue = new Properties();
        queue.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0);
        when(amqp.getQueueProperties("remark.note.liked.times.queue")).thenReturn(queue);
        LikeMetricsTask task = new LikeMetricsTask(redis, amqp, mock(LikeWriteBehindTask.class));
        long now = System.currentTimeMillis();

        task.sampleAt(now);
        assertTrue(task.rejectingWrites());
        task.sampleAt(now + 1);
        assertTrue(task.rejectingWrites());
        task.sampleAt(now + 2);
        assertFalse(task.rejectingWrites());
        task.sampleAt(now - 6000);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "lastSampleAt", now - 6000);
        assertFalse(task.rejectingWrites());
    }

    @Test
    void staleSamplerChecksStreamLengthBeforeRejectingWrites() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(0L, 50001L);
        LikeMetricsTask task = new LikeMetricsTask(redis, mock(AmqpAdmin.class),
                mock(LikeWriteBehindTask.class));

        assertFalse(task.rejectingWrites());
        org.springframework.test.util.ReflectionTestUtils.setField(task, "lastSampleAt", 0L);
        assertTrue(task.rejectingWrites());
    }

    @Test
    void staleSamplerRejectsWhenRedisLengthCannotBeRead() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.size(LikeCacheKeys.CHANGE_STREAM))
                .thenThrow(new DataAccessResourceFailureException("Redis unavailable"));
        LikeMetricsTask task = new LikeMetricsTask(redis, mock(AmqpAdmin.class),
                mock(LikeWriteBehindTask.class));

        assertTrue(task.rejectingWrites());
    }

    @Test
    void slowDetailedMetricsDoNotInvalidateFreshBacklogReading() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(0L);
        when(streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP))
                .thenThrow(new DataAccessResourceFailureException("pending unavailable"));
        LikeMetricsTask task = new LikeMetricsTask(redis, mock(AmqpAdmin.class),
                mock(LikeWriteBehindTask.class));

        task.sampleAt(System.currentTimeMillis());

        assertFalse(task.rejectingWrites());
    }

    @Test
    void alertsWhileConsumerBatchIsStalled() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        LikeWriteBehindTask writer = mock(LikeWriteBehindTask.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(0L);
        when(writer.activeBatchMillis(anyLong())).thenReturn(31000L);
        LikeMetricsTask task = new LikeMetricsTask(redis, mock(AmqpAdmin.class), writer);
        Logger logger = (Logger) LoggerFactory.getLogger(LikeMetricsTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.sampleAt(32000);
            assertTrue(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT 点赞消费批次处理超时")));
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void freshUndeliveredEntryContributesToLag() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AmqpAdmin amqp = mock(AmqpAdmin.class);
        PendingMessagesSummary pending = mock(PendingMessagesSummary.class);
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);
        Map<Object, Object> data = new LinkedHashMap<>();
        data.put("userId", "2");
        MapRecord<String, Object, Object> fresh = MapRecord.create(LikeCacheKeys.CHANGE_STREAM, data)
                .withId(RecordId.of("2000-0"));
        when(redis.opsForStream()).thenReturn(streams);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(1L);
        when(streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP)).thenReturn(pending);
        when(pending.getTotalPendingMessages()).thenReturn(0L);
        when(streams.groups(LikeCacheKeys.CHANGE_STREAM)).thenReturn(groups);
        when(groups.stream()).thenAnswer(invocation -> Stream.of(group));
        when(group.groupName()).thenReturn(LikeCacheKeys.CHANGE_GROUP);
        when(group.lastDeliveredId()).thenReturn("1000-0");
        when(streams.range(eq(LikeCacheKeys.CHANGE_STREAM), any(Range.class), any(Limit.class)))
                .thenReturn(java.util.List.of(fresh));
        Properties queue = new Properties();
        queue.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0);
        when(amqp.getQueueProperties("remark.note.liked.times.queue")).thenReturn(queue);
        LikeMetricsTask task = new LikeMetricsTask(redis, amqp, mock(LikeWriteBehindTask.class));
        Logger logger = (Logger) LoggerFactory.getLogger(LikeMetricsTask.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            task.sampleAt(63000);
            assertTrue(events.list.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("ALERT 点赞落库延迟超阈值 lagMs=61000")));
            assertTrue(events.list.stream().noneMatch(e ->
                    e.getFormattedMessage().contains("Stream 存在已确认残留")));
        } finally {
            logger.detachAppender(events);
        }
    }
}
