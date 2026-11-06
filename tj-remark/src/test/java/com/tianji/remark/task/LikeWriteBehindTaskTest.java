package com.tianji.remark.task;

import com.tianji.remark.domain.dto.LikeChange;
import com.tianji.remark.service.LikeChangePersistenceService;
import com.tianji.remark.service.LikeDeadLetterService;
import com.tianji.remark.mq.LikeTimesPublisher;
import com.tianji.remark.exception.LikePublishRetryableException;
import com.tianji.remark.utils.LikeCacheKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class LikeWriteBehindTaskTest {
    private StringRedisTemplate redis;
    private StreamOperations<String, Object, Object> streams;
    private LikeChangePersistenceService persistence;
    private LikeTimesPublisher publisher;
    private LikeDeadLetterService deadLetters;
    private LikeWriteBehindTask task;
    private RedissonClient redisson;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        streams = mock(StreamOperations.class);
        persistence = mock(LikeChangePersistenceService.class);
        publisher = mock(LikeTimesPublisher.class);
        deadLetters = mock(LikeDeadLetterService.class);
        redisson = mock(RedissonClient.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(persistence.persistBatch(any())).thenReturn(Collections.emptyList());
        when(streams.pending(eq(LikeCacheKeys.CHANGE_STREAM), eq(LikeCacheKeys.CHANGE_GROUP),
                any(Range.class), anyLong()))
                .thenReturn(new PendingMessages(LikeCacheKeys.CHANGE_GROUP, Collections.emptyList()));
        task = new LikeWriteBehindTask(redis, redisson, persistence,
                publisher, deadLetters, 1000, 60000);
    }

    @Test
    void invalidLikedGoesToDeadLetterBeforeAcknowledgement() throws Exception {
        MapRecord<String, Object, Object> bad = record("abc");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(bad), Collections.emptyList());
        when(persistence.persistBatch(Collections.emptyList())).thenReturn(Collections.emptyList());

        assertEquals(1, task.drainOneStreamBatch(new LinkedHashMap<>()));

        verify(deadLetters).save(bad, "liked非法");
        verify(streams).acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP, bad.getId());
        verify(streams).delete(LikeCacheKeys.CHANGE_STREAM, bad.getId());
    }

    @Test
    void deadLetterFailureLeavesOriginalPending() throws Exception {
        MapRecord<String, Object, Object> bad = record("abc");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(bad), Collections.emptyList(), List.of(bad), Collections.emptyList());
        doThrow(new DataAccessResourceFailureException("db down")).when(deadLetters).save(bad, "liked非法");
        when(streams.range(eq(LikeCacheKeys.CHANGE_STREAM), any(Range.class))).thenReturn(List.of(bad));

        assertEquals(0, task.drainOneStreamBatch(new LinkedHashMap<>()));
        verify(streams, never()).acknowledge(eq(LikeCacheKeys.CHANGE_STREAM),
                eq(LikeCacheKeys.CHANGE_GROUP), any(RecordId.class));
        verify(streams, never()).delete(eq(LikeCacheKeys.CHANGE_STREAM), any(RecordId.class));
    }

    @Test
    void failedDeadLetterIsRetriedAndThenAcknowledged() throws Exception {
        task = new LikeWriteBehindTask(redis, redisson, persistence, publisher, deadLetters, 0, 0);
        MapRecord<String, Object, Object> bad = record("abc");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(bad), Collections.emptyList(), List.of(bad), Collections.emptyList());
        when(streams.range(eq(LikeCacheKeys.CHANGE_STREAM), any(Range.class))).thenReturn(List.of(bad));
        doThrow(new DataAccessResourceFailureException("db down")).doNothing()
                .when(deadLetters).save(bad, "liked非法");

        assertEquals(0, task.drainOneStreamBatch(new LinkedHashMap<>()));
        verify(streams, never()).acknowledge(eq(LikeCacheKeys.CHANGE_STREAM),
                eq(LikeCacheKeys.CHANGE_GROUP), any(RecordId.class));
        assertEquals(1, task.drainOneStreamBatch(new LinkedHashMap<>()));
        verify(streams).acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP, bad.getId());
        Map<?, ?> tracked = (Map<?, ?>) ReflectionTestUtils.getField(task, "deadLetterBackoffs");
        assertFalse(tracked.containsKey(bad.getId().getValue()));
    }

    @Test
    void deadLetterBackoffStateEvictsOldestEntryAtCap() throws Exception {
        Map<String, PublishBackoff> tracked = (Map<String, PublishBackoff>)
                ReflectionTestUtils.getField(task, "deadLetterBackoffs");
        for (int i = 0; i < 1000; i++) {
            tracked.put(i + "-0", new PublishBackoff(1000, 60000));
        }
        MapRecord<String, Object, Object> bad = record("abc", "1001-0");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(bad), Collections.emptyList());
        doThrow(new DataAccessResourceFailureException("db down")).when(deadLetters).save(bad, "liked非法");

        task.drainOneStreamBatch(new LinkedHashMap<>());

        assertEquals(1000, tracked.size());
        assertFalse(tracked.containsKey("0-0"));
        assertTrue(tracked.containsKey("1001-0"));
    }

    @Test
    void recoveryMayFinishWithOnlyMarkedBadRecordPending() throws Exception {
        MapRecord<String, Object, Object> bad = record("abc");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(bad), Collections.emptyList());
        when(streams.range(eq(LikeCacheKeys.CHANGE_STREAM), any(Range.class))).thenReturn(List.of(bad));
        PendingMessage pending = new PendingMessage(bad.getId(),
                Consumer.from(LikeCacheKeys.CHANGE_GROUP, LikeCacheKeys.WORKER_NAME), Duration.ZERO, 1);
        when(streams.pending(eq(LikeCacheKeys.CHANGE_STREAM), eq(LikeCacheKeys.CHANGE_GROUP),
                any(Range.class), anyLong()))
                .thenReturn(new PendingMessages(LikeCacheKeys.CHANGE_GROUP, List.of(pending)));
        when(streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP))
                .thenReturn(new PendingMessagesSummary(LikeCacheKeys.CHANGE_GROUP, 1,
                        Range.closed("1-0", "1-0"), Map.of(LikeCacheKeys.WORKER_NAME, 1L)));
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(1L);
        doThrow(new DataAccessResourceFailureException("db down")).when(deadLetters).save(bad, "liked非法");

        assertEquals(0, task.drainOneStreamBatch(new LinkedHashMap<>()));

        assertEquals(true, task.isDrainComplete());
        // 退避表达到上限时可淘汰内存标记；完成一轮 PEL 扫描后不能因此卡死启动。
        ((Map<?, ?>) ReflectionTestUtils.getField(task, "deadLetterBackoffs")).clear();
        task.drainOneStreamBatch(new LinkedHashMap<>());
        assertTrue(task.isDrainComplete());
    }

    @Test
    void deletedPendingEntryIsAcknowledgedWithoutDeletingAnotherRecord() throws Exception {
        RecordId missingId = RecordId.of("3-0");
        PendingMessage ghost = new PendingMessage(missingId,
                Consumer.from(LikeCacheKeys.CHANGE_GROUP, LikeCacheKeys.WORKER_NAME), Duration.ZERO, 1);
        when(streams.pending(eq(LikeCacheKeys.CHANGE_STREAM), eq(LikeCacheKeys.CHANGE_GROUP),
                any(Range.class), anyLong()))
                .thenReturn(new PendingMessages(LikeCacheKeys.CHANGE_GROUP, List.of(ghost)));
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Collections.emptyList());
        when(streams.range(eq(LikeCacheKeys.CHANGE_STREAM), any(Range.class)))
                .thenReturn(Collections.emptyList());

        assertEquals(1, task.drainOneStreamBatch(new LinkedHashMap<>()));

        verify(streams).acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP, missingId);
        verify(streams, never()).delete(eq(LikeCacheKeys.CHANGE_STREAM), any(RecordId.class));
    }

    @Test
    void acknowledgedButNotDeletedEntryDoesNotBlockRecovery() throws Exception {
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Collections.emptyList());
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(1L);
        when(streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP))
                .thenReturn(new PendingMessagesSummary(LikeCacheKeys.CHANGE_GROUP, 0,
                        Range.unbounded(), Collections.emptyMap()));

        task.drainOneStreamBatch(new LinkedHashMap<>());

        assertTrue(task.isDrainComplete());
    }

    @Test
    void unreadNewEntryStillBlocksRecovery() throws Exception {
        MapRecord<String, Object, Object> fresh = record("1");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Collections.emptyList(), List.of(fresh));
        when(persistence.persistBatch(any())).thenReturn(Collections.emptyList());
        when(streams.size(LikeCacheKeys.CHANGE_STREAM)).thenReturn(1L);
        when(streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP))
                .thenReturn(new PendingMessagesSummary(LikeCacheKeys.CHANGE_GROUP, 0,
                        Range.unbounded(), Collections.emptyMap()));

        task.drainOneStreamBatch(new LinkedHashMap<>());

        assertFalse(task.isDrainComplete());
    }

    @Test
    void recoveryCanAckPersistedChangeWhenMqIsDown() throws Exception {
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        MapRecord<String, Object, Object> valid = record("1");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(valid), Collections.emptyList());
        LikeChange snapshot = new LikeChange();
        snapshot.setBizType("QA");
        snapshot.setBizId(2L);
        snapshot.setLikedTimes(1);
        snapshot.setVersion(3L);
        when(persistence.persistBatch(any())).thenReturn(List.of(snapshot));
        doThrow(new LikePublishRetryableException("mq down")).when(publisher).publish(List.of(snapshot));
        Map<String, LikeChange> failed = new LinkedHashMap<>();

        assertEquals(1, task.drainOneStreamBatch(failed));

        assertEquals(snapshot, failed.get("QA:2"));
        org.mockito.InOrder order = inOrder(hashes, streams);
        order.verify(hashes).put(eq("remark:like:publish:retry"), eq("QA:2"), any(String.class));
        order.verify(streams).acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP,
                valid.getId());
        verify(streams).acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP, valid.getId());
    }

    @Test
    void recoveryKeepsStreamWhenRetryMarkerCannotBeSaved() {
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        MapRecord<String, Object, Object> valid = record("1");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(valid), Collections.emptyList());
        LikeChange snapshot = new LikeChange();
        snapshot.setBizType("QA");
        snapshot.setBizId(2L);
        when(persistence.persistBatch(any())).thenReturn(List.of(snapshot));
        doThrow(new LikePublishRetryableException("mq down")).when(publisher).publish(List.of(snapshot));
        doThrow(new DataAccessResourceFailureException("redis down")).when(hashes)
                .put(eq(LikeCacheKeys.RETRY_SNAPSHOTS), eq("QA:2"), any(String.class));

        assertThrows(DataAccessResourceFailureException.class,
                () -> task.drainOneStreamBatch(new LinkedHashMap<>()));
        verify(streams, never()).acknowledge(eq(LikeCacheKeys.CHANGE_STREAM),
                eq(LikeCacheKeys.CHANGE_GROUP), any(RecordId.class));
    }

    @Test
    void normalConsumptionKeepsStreamPendingWhenMqIsDown() throws Exception {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RLock lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(redisson.getLock(LikeCacheKeys.WORKER_LOCK)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        MapRecord<String, Object, Object> valid = record("1");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(valid), Collections.emptyList());
        LikeChange snapshot = new LikeChange();
        snapshot.setBizType("QA");
        snapshot.setBizId(2L);
        when(persistence.persistBatch(any())).thenReturn(List.of(snapshot));
        doThrow(new LikePublishRetryableException("mq down")).when(publisher).publish(List.of(snapshot));

        task.flushChangesAndPublish();

        verify(streams, never()).acknowledge(eq(LikeCacheKeys.CHANGE_STREAM),
                eq(LikeCacheKeys.CHANGE_GROUP), any(RecordId.class));
        verify(streams, never()).delete(eq(LikeCacheKeys.CHANGE_STREAM), any(RecordId.class));
        assertEquals(1, task.failureCount());
        assertEquals(0, task.lastSuccessAtMillis());
    }

    @Test
    void connectionFailureCountsAndBacksOffWithoutAcknowledging() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RLock lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(redisson.getLock(LikeCacheKeys.WORKER_LOCK)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        MapRecord<String, Object, Object> valid = record("1");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(valid), Collections.emptyList());
        when(persistence.persistBatch(any()))
                .thenThrow(new CannotCreateTransactionException("pool exhausted"));

        task.flushChangesAndPublish();

        assertEquals(1, task.failureCount());
        assertTrue(((PublishBackoff) ReflectionTestUtils.getField(task, "backoff"))
                .shouldSkip(System.currentTimeMillis()));
        verify(streams, never()).acknowledge(eq(LikeCacheKeys.CHANGE_STREAM),
                eq(LikeCacheKeys.CHANGE_GROUP), any(RecordId.class));
        verify(streams, never()).delete(eq(LikeCacheKeys.CHANGE_STREAM), any(RecordId.class));
    }

    @Test
    void successfulPublishRecordsLastSuccessTime() throws Exception {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RLock lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(redisson.getLock(LikeCacheKeys.WORKER_LOCK)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        MapRecord<String, Object, Object> valid = record("1");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(valid), Collections.emptyList());
        LikeChange snapshot = new LikeChange();
        snapshot.setBizType("QA");
        snapshot.setBizId(2L);
        when(persistence.persistBatch(any())).thenReturn(List.of(snapshot));

        task.flushChangesAndPublish();

        assertTrue(task.lastSuccessAtMillis() > 0);
        assertEquals(0, task.failureCount());
    }

    @Test
    void exposesBusyTimeOnlyWhileHoldingConsumerLock() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RLock lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(redisson.getLock(LikeCacheKeys.WORKER_LOCK)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        MapRecord<String, Object, Object> valid = record("1");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(valid), Collections.emptyList());
        when(persistence.persistBatch(any())).thenAnswer(invocation -> {
            assertTrue(task.activeBatchMillis(System.currentTimeMillis() + 1) > 0);
            return Collections.emptyList();
        });

        task.flushChangesAndPublish();

        assertEquals(0, task.activeBatchMillis(System.currentTimeMillis()));
    }

    @Test
    void failedDeadLetterDoesNotBlockNewValidRecord() throws Exception {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RLock lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(redisson.getLock(LikeCacheKeys.WORKER_LOCK)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        MapRecord<String, Object, Object> bad = record("abc");
        MapRecord<String, Object, Object> valid = record("1", "2-0");
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(bad), List.of(valid));
        doThrow(new DataAccessResourceFailureException("db down")).when(deadLetters).save(bad, "liked非法");
        LikeChange snapshot = new LikeChange();
        snapshot.setBizType("QA");
        snapshot.setBizId(2L);
        when(persistence.persistBatch(any())).thenReturn(List.of(snapshot));

        task.flushChangesAndPublish();

        verify(streams).acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP, valid.getId());
        verify(streams).delete(LikeCacheKeys.CHANGE_STREAM, valid.getId());
        verify(streams, never()).acknowledge(LikeCacheKeys.CHANGE_STREAM,
                LikeCacheKeys.CHANGE_GROUP, bad.getId());
        ArgumentCaptor<StreamReadOptions> readOptions = ArgumentCaptor.forClass(StreamReadOptions.class);
        verify(streams, times(2)).read(any(Consumer.class), readOptions.capture(), any(StreamOffset.class));
        assertEquals(500L, readOptions.getAllValues().get(0).getCount());
        assertEquals(500L, readOptions.getAllValues().get(1).getCount());
    }

    @Test
    void olderPendingPagesFinishBeforeFreshChangesAreDelivered() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RLock lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LikeCacheKeys.READY_KEY)).thenReturn("READY");
        when(redisson.getLock(LikeCacheKeys.WORKER_LOCK)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        List<MapRecord<String, Object, Object>> firstPendingPage = IntStream.rangeClosed(1, 500)
                .mapToObj(index -> record("1", index + "-0"))
                .collect(java.util.stream.Collectors.toList());
        MapRecord<String, Object, Object> newerUnlike = record("0", "502-0");
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenAnswer(invocation -> reads.getAndIncrement() == 0
                        ? firstPendingPage.subList(0, invocation.getArgument(1, StreamReadOptions.class)
                                .getCount().intValue())
                        : List.of(newerUnlike));
        PendingMessage laterPending = new PendingMessage(RecordId.of("501-0"),
                Consumer.from(LikeCacheKeys.CHANGE_GROUP, LikeCacheKeys.WORKER_NAME), Duration.ZERO, 1);
        when(streams.pending(eq(LikeCacheKeys.CHANGE_STREAM), eq(LikeCacheKeys.CHANGE_GROUP),
                any(Range.class), anyLong()))
                .thenReturn(new PendingMessages(LikeCacheKeys.CHANGE_GROUP, List.of(laterPending)));

        task.flushChangesAndPublish();

        // 较新的取消点赞不能越过仍未处理的旧 pending。
        verify(streams, times(1)).read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class));
    }

    private MapRecord<String, Object, Object> record(String liked) {
        return record(liked, "1-0");
    }

    private MapRecord<String, Object, Object> record(String liked, String id) {
        Map<Object, Object> data = new LinkedHashMap<>();
        data.put("userId", "1");
        data.put("bizType", "QA");
        data.put("bizId", "2");
        data.put("liked", liked);
        data.put("likedTimes", "1");
        data.put("version", "3");
        return MapRecord.create(LikeCacheKeys.CHANGE_STREAM, data).withId(RecordId.of(id));
    }
}
