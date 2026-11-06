package com.tianji.remark.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tianji.api.client.learning.LearningClient;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.remark.domain.dto.LikeChange;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.mq.LikeTimesPublisher;
import com.tianji.remark.task.LikeCacheInitializer;
import com.tianji.remark.task.LikeMetricsTask;
import com.tianji.remark.task.LikeWriteBehindTask;
import com.tianji.remark.utils.LikeCacheKeys;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 仅连接带专用标记的临时 Redis；数据库和消息发送使用 mock。 */
@EnabledIfEnvironmentVariable(named = "TJ_REMARK_TEST_REDIS_PORT", matches = "\\d+")
class LikeRedisIntegrationTest {
    private static final String TEST_MARKER = "codex:remark:test-instance";
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private JdbcTemplate jdbc;
    private LikeTargetService targets;
    private LikeCacheService cache;
    private LikeTimesPublisher publisher;
    private LikeWriteBehindTask worker;
    private Set<Long> existing;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getenv("TJ_REMARK_TEST_REDIS_PORT")));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        assertEquals("01a0f147-remark-repair", redis.opsForValue().get(TEST_MARKER),
                "禁止连接未标记的 Redis");
        redis.execute((RedisCallback<Void>) connection -> {
            connection.flushDb();
            return null;
        });
        redis.opsForValue().set(TEST_MARKER, "01a0f147-remark-repair");
        redis.opsForValue().set(LikeCacheKeys.READY_KEY, "READY");
        RecordId marker = redis.opsForStream().add(LikeCacheKeys.CHANGE_STREAM, Map.of("initializer", "ready"));
        redis.opsForStream().createGroup(LikeCacheKeys.CHANGE_STREAM, ReadOffset.latest(), LikeCacheKeys.CHANGE_GROUP);
        redis.opsForStream().delete(LikeCacheKeys.CHANGE_STREAM, marker);
        existing = ConcurrentHashMap.newKeySet();
        existing.addAll(Set.of(2L, 3L));
        LearningClient learning = mock(LearningClient.class);
        when(learning.queryExistingReplyIds(anyList())).thenAnswer(invocation -> {
            Set<Long> found = new java.util.HashSet<>(invocation.getArgument(0, List.class));
            found.retainAll(existing);
            return found;
        });
        jdbc = mock(JdbcTemplate.class);
        targets = new LikeTargetService(learning, redis, jdbc);
        cache = new LikeCacheService(redis, mock(LikeMetricsTask.class), targets);
        publisher = mock(LikeTimesPublisher.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(LikeCacheKeys.WORKER_LOCK)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        worker = new LikeWriteBehindTask(redis, redisson,
                new LikeChangePersistenceService(jdbc, targets), publisher,
                mock(LikeDeadLetterService.class), 1000, 60000);
    }

    @AfterEach
    void close() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void firstLikeRepeatedLikeUnlikeAndRepeatedUnlikeStayConsistent() {
        cache.change(1L, "QA", 2L, true);
        cache.change(1L, "QA", 2L, true);
        assertEquals(Set.of(2L), cache.queryMyLikedBizIds(1L, "QA", List.of(2L)));
        assertEquals(1, count());
        assertEquals(1L, redis.opsForStream().size(LikeCacheKeys.CHANGE_STREAM));
        worker.flushChangesAndPublish();
        verify(jdbc).update(startsWith("INSERT"), eq(1L), eq(2L), eq("QA"));

        cache.change(1L, "QA", 2L, false);
        cache.change(1L, "QA", 2L, false);
        assertEquals(Set.of(), cache.queryMyLikedBizIds(1L, "QA", List.of(2L)));
        assertEquals(0, count());
        assertEquals(1L, redis.opsForStream().size(LikeCacheKeys.CHANGE_STREAM));
        worker.flushChangesAndPublish();
        verify(jdbc).update(startsWith("DELETE"), eq(1L), eq(2L), eq("QA"));
        assertEquals(0L, redis.opsForStream().size(LikeCacheKeys.CHANGE_STREAM));
    }

    @Test
    void repeatedRecoveryPublishesSameAbsoluteCountAndVersionWithoutAddingLikes() {
        cache.change(1L, "QA", 2L, true);
        Object version = redis.opsForHash().get(LikeCacheKeys.versionHash("QA"), "2");
        LikeCacheInitializer initializer = initializer(mock(LikedRecordMapper.class));
        for (int attempt = 0; attempt < 2; attempt++) {
            redis.opsForHash().put(LikeCacheKeys.RETRY_SNAPSHOTS, "QA:2", "retry-" + attempt);
            initializer.retryFailedSnapshots();
            assertEquals(1, count());
            assertEquals(Set.of(2L), cache.queryMyLikedBizIds(1L, "QA", List.of(2L)));
        }
        ArgumentCaptor<List<LikeChange>> sent = ArgumentCaptor.forClass(List.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(sent.capture());
        for (List<LikeChange> snapshots : sent.getAllValues()) {
            assertEquals(1, snapshots.get(0).getLikedTimes());
            assertEquals(Long.valueOf(version.toString()), snapshots.get(0).getVersion());
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void concurrentChangesCannotPairAnOldCountWithANewVersion() throws Exception {
        cache.change(1L, "QA", 2L, true);
        LikeCacheInitializer initializer = initializer(mock(LikedRecordMapper.class));
        LikeChange target = new LikeChange();
        target.setBizType("QA");
        target.setBizId(2L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<LikeChange> observed = new ArrayList<>();
        try {
            Future<?> writes = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 50; i++) {
                    cache.change(1L, "QA", 2L, false);
                    cache.change(1L, "QA", 2L, true);
                }
            });
            Future<?> reads = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 100; i++) {
                    observed.add(ReflectionTestUtils.invokeMethod(initializer, "freshSnapshot", target, false));
                }
            });
            start.countDown();
            writes.get();
            reads.get();
        } finally {
            executor.shutdownNow();
        }
        Map<Long, Integer> countsByVersion = new HashMap<>();
        redis.opsForStream().range(LikeCacheKeys.CHANGE_STREAM,
                org.springframework.data.domain.Range.unbounded()).forEach(record -> {
            Map<Object, Object> fields = record.getValue();
            countsByVersion.put(Long.valueOf(fields.get("version").toString()),
                    Integer.valueOf(fields.get("likedTimes").toString()));
        });
        assertEquals(100, observed.size());
        for (LikeChange snapshot : observed) {
            assertEquals(countsByVersion.get(snapshot.getVersion()), snapshot.getLikedTimes());
        }
        assertEquals(1, count());
    }

    @Test
    void missingTargetDoesNotCreateAnyLikeData() {
        Set<String> before = redis.keys(LikeCacheKeys.PREFIX + "*");

        assertThrows(BadRequestException.class, () -> cache.change(1L, "QA", 999L, true));

        assertEquals(before, redis.keys(LikeCacheKeys.PREFIX + "*"));
        verifyNoInteractions(jdbc, publisher);
    }

    @Test
    void deletedTargetIsRemovedByStreamRecoveryAndSnapshotRetry() {
        cache.change(1L, "QA", 2L, true);
        cache.change(2L, "QA", 2L, true);
        existing.remove(2L);
        redis.opsForHash().put(LikeCacheKeys.RETRY_SNAPSHOTS, "QA:2", "old-retry");

        worker.flushChangesAndPublish();
        initializer(mock(LikedRecordMapper.class)).retryFailedSnapshots();

        assertEquals(0, count());
        assertFalse(redis.opsForSet().isMember(LikeCacheKeys.userBizSet("QA", 1L), "2"));
        assertFalse(redis.opsForSet().isMember(LikeCacheKeys.userBizSet("QA", 2L), "2"));
        assertNull(redis.opsForHash().get(LikeCacheKeys.versionHash("QA"), "2"));
        assertEquals(0L, redis.opsForStream().size(LikeCacheKeys.CHANGE_STREAM));
        assertEquals(0L, redis.opsForHash().size(LikeCacheKeys.RETRY_SNAPSHOTS));
        verify(jdbc, never()).update(startsWith("INSERT"), any(Object[].class));
        verify(jdbc, org.mockito.Mockito.times(2)).update(
                "DELETE FROM liked_record WHERE biz_type = ? AND biz_id IN (?)", "QA", 2L);
        verify(publisher, org.mockito.Mockito.times(2)).publish(List.of());
    }

    @Test
    void databaseRebuildDoesNotReloadDeletedTargets() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                LikedRecord.class);
        LikedRecordMapper mapper = mock(LikedRecordMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                new LikedRecord().setId(1L).setUserId(1L).setBizType("QA").setBizId(2L),
                new LikedRecord().setId(2L).setUserId(9L).setBizType("QA").setBizId(999L),
                new LikedRecord().setId(3L).setUserId(9L).setBizType("NOTE").setBizId(999L)), List.of());
        redis.opsForValue().set(LikeCacheKeys.READY_KEY, "BUILDING");

        ReflectionTestUtils.invokeMethod(initializer(mapper), "loadExistingRecords");

        assertEquals(1D, redis.opsForZSet().score(LikeCacheKeys.countSortedSet("QA"), "2"));
        assertTrue(redis.opsForSet().isMember(LikeCacheKeys.userBizSet("QA", 1L), "2"));
        assertFalse(redis.opsForSet().isMember(LikeCacheKeys.userBizSet("QA", 9L), "999"));
        assertNull(redis.opsForZSet().score(LikeCacheKeys.countSortedSet("QA"), "999"));
        verify(jdbc).update("DELETE FROM liked_record WHERE biz_type = ? AND biz_id IN (?)", "QA", 999L);
        assertTrue(redis.opsForSet().isMember(LikeCacheKeys.userBizSet("NOTE", 9L), "999"));
        assertEquals(1D, redis.opsForZSet().score(LikeCacheKeys.countSortedSet("NOTE"), "999"));
        verify(jdbc, never()).update(
                "DELETE FROM liked_record WHERE biz_type = ? AND biz_id IN (?)", "NOTE", 999L);
    }

    @Test
    void parallelLikesAreIdempotentPerUserAndAddForDifferentUsers() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> repeated = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                repeated.add(executor.submit(() -> cache.change(1L, "QA", 2L, true)));
            }
            for (Future<?> request : repeated) {
                request.get();
            }
            assertEquals(1, count());
            Future<?> second = executor.submit(() -> cache.change(2L, "QA", 2L, true));
            Future<?> third = executor.submit(() -> cache.change(3L, "QA", 2L, true));
            second.get();
            third.get();
            assertEquals(3, count());
            assertEquals(3L, redis.opsForStream().size(LikeCacheKeys.CHANGE_STREAM));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void noteKeepsItsLikeUnlikePersistenceAndRecoveryContract() {
        cache.change(1L, "NOTE", 999L, true);
        cache.change(1L, "NOTE", 999L, true);
        assertEquals(1, cache.queryLikedTimes("NOTE", List.of(999L)).get(999L));
        assertEquals(Set.of(999L), cache.queryMyLikedBizIds(1L, "NOTE", List.of(999L)));
        worker.flushChangesAndPublish();
        verify(jdbc).update(startsWith("INSERT"), eq(1L), eq(999L), eq("NOTE"));

        redis.opsForHash().put(LikeCacheKeys.RETRY_SNAPSHOTS, "NOTE:999", "note-retry");
        initializer(mock(LikedRecordMapper.class)).retryFailedSnapshots();
        assertEquals(0L, redis.opsForHash().size(LikeCacheKeys.RETRY_SNAPSHOTS));
        assertEquals(1, cache.queryLikedTimes("NOTE", List.of(999L)).get(999L));

        cache.change(1L, "NOTE", 999L, false);
        cache.change(1L, "NOTE", 999L, false);
        worker.flushChangesAndPublish();
        assertEquals(0, cache.queryLikedTimes("NOTE", List.of(999L)).get(999L));
        assertEquals(Set.of(), cache.queryMyLikedBizIds(1L, "NOTE", List.of(999L)));
        verify(jdbc).update(startsWith("DELETE"), eq(1L), eq(999L), eq("NOTE"));
        verify(jdbc, never()).update(
                "DELETE FROM liked_record WHERE biz_type = ? AND biz_id IN (?)", "NOTE", 999L);
    }

    @Test
    void deletedOnlyRetryBatchUsesRealPublisherAndRemovesMarkers() {
        org.springframework.amqp.rabbit.core.RabbitTemplate rabbit =
                mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        publisher = new LikeTimesPublisher(rabbit);
        existing.remove(2L);
        redis.opsForHash().put(LikeCacheKeys.RETRY_SNAPSHOTS, "QA:2", "deleted-retry");
        LikeCacheInitializer initializer = initializer(mock(LikedRecordMapper.class));

        initializer.retryFailedSnapshots();
        initializer.retryFailedSnapshots();

        assertEquals(0L, redis.opsForHash().size(LikeCacheKeys.RETRY_SNAPSHOTS));
        verifyNoInteractions(rabbit);
        verify(jdbc).update("DELETE FROM liked_record WHERE biz_type = ? AND biz_id IN (?)", "QA", 2L);
    }

    private LikeCacheInitializer initializer(LikedRecordMapper mapper) {
        return new LikeCacheInitializer(mapper, redis, mock(RedissonClient.class), worker, publisher, targets);
    }

    private int count() {
        return cache.queryLikedTimes("QA", List.of(2L)).get(2L);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
