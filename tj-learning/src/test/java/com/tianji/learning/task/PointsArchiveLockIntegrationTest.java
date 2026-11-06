package com.tianji.learning.task;

import com.tianji.common.autoconfigure.redisson.aspect.LockAspect;
import com.tianji.learning.mapper.points.PointsMessageMapper;
import com.tianji.learning.service.points.IPointsBoardSeasonService;
import com.tianji.learning.service.points.IPointsBoardService;
import com.tianji.learning.service.points.impl.PointsBoardServiceImpl;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.handler.impl.MethodJobHandler;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用真实 Spring 代理与 XXL MethodJobHandler 验证锁生效；锁后端在隔离 JVM 中模拟。 */
class PointsArchiveLockIntegrationTest {
    @SuppressWarnings("unchecked")
    @Test
    void xxlHandlersKeepParallelShardsButBlockCleanupAndScoreChanges() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        RReadWriteLock rw = mock(RReadWriteLock.class);
        RLock read = mock(RLock.class);
        RLock write = mock(RLock.class);
        ReentrantReadWriteLock backend = new ReentrantReadWriteLock();
        when(client.getReadWriteLock("lock:points:archive")).thenReturn(rw);
        when(rw.readLock()).thenReturn(read);
        when(rw.writeLock()).thenReturn(write);
        when(read.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(i -> backend.readLock().tryLock(i.getArgument(0), i.getArgument(2)));
        when(write.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(i -> backend.writeLock().tryLock(i.getArgument(0), i.getArgument(2)));
        doAnswer(i -> { backend.readLock().unlock(); return null; }).when(read).unlock();
        doAnswer(i -> { backend.writeLock().unlock(); return null; }).when(write).unlock();

        IPointsBoardService boards = mock(IPointsBoardService.class);
        IPointsBoardSeasonService seasons = mock(IPointsBoardSeasonService.class);
        when(seasons.querySeasonByTime(any())).thenReturn(12);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.hasKey(anyString())).thenReturn(true);
        CountDownLatch bothReading = new CountDownLatch(2);
        CountDownLatch releaseReaders = new CountDownLatch(1);
        when(boards.queryCurrentBoardList(anyString(), anyInt(), anyInt())).thenAnswer(i -> {
            bothReading.countDown();
            if (!releaseReaders.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test reader timeout");
            return List.of();
        });
        PointBoardPersistentTask task = proxy(new PointBoardPersistentTask(boards, seasons, redis,
                mock(PointsMessageMapper.class)), client);
        PointsBoardServiceImpl scoreWriter = proxy(new PointsBoardServiceImpl(redis, null), client);
        Map<Method, XxlJob> handlers = MethodIntrospector.selectMethods(task.getClass(),
                (MethodIntrospector.MetadataLookup<XxlJob>) m -> AnnotatedElementUtils.findMergedAnnotation(m, XxlJob.class));
        assertEquals(3, handlers.size(), "XXL must discover all handlers after creating the Spring proxy");
        Method save = handlers.entrySet().stream().filter(e -> e.getValue().value().equals("savePointsBoardToDBJob"))
                .findFirst().orElseThrow().getKey();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> executeShard(task, save, 0));
            Future<?> second = executor.submit(() -> executeShard(task, save, 1));
            assertTrue(bothReading.await(5, TimeUnit.SECONDS), "Read locks must allow simultaneous shards");
            assertThrows(RuntimeException.class, task::clearPointsBoardFromRedis);
            assertThrows(RuntimeException.class, () -> scoreWriter.updateBoardScore(1L, "202609", 10));
            verify(redis, never()).unlink(anyString());
            verify(zset, never()).add(anyString(), anyString(), anyDouble());
            releaseReaders.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            scoreWriter.updateBoardScore(1L, "202609", 10);
            task.clearPointsBoardFromRedis();
            verify(zset).add("board:202609", "1", 10D);
            verify(redis).unlink(anyString());
        } finally {
            releaseReaders.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void executeShard(PointBoardPersistentTask task, Method method, int index) {
        XxlJobContext.setXxlJobContext(new XxlJobContext(1L, "", null, index, 2));
        try {
            new MethodJobHandler(task, method, null, null).execute();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }
    }

    private <T> T proxy(T target, RedissonClient client) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new LockAspect(client));
        return factory.getProxy();
    }
}
