package com.tianji.aigc.longterm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 有界工作线程处理同一用户、同一会话的轮次；模型调用不持有数据库事务。 */
@Slf4j
public final class UserMemoryWorker implements AutoCloseable {
    private final UserMemoryStore store;
    private final UserMemoryExtractor extractor;
    private final ObservationRegistry observations;
    @Value("${tj.ai.observation.memory-details:false}")
    private boolean captureMemoryDetails;
    private final Semaphore slots = new Semaphore(2);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "user-memory-worker");
                thread.setDaemon(true);
                return thread;
            });

    public UserMemoryWorker(UserMemoryStore store, UserMemoryExtractor extractor, ObservationRegistry observations) {
        this.store = store;
        this.extractor = extractor;
        this.observations = observations;
    }

    @Scheduled(fixedDelay = 5000)
    public void poll() {
        if (!slots.tryAcquire()) return;
        UserMemoryStore.Batch batch = null;
        try {
            batch = store.claimDue(3, Duration.ofSeconds(30));
            if (batch == null) { slots.release(); return; }
            UserMemoryStore.Batch claimed = batch;
            executor.execute(() -> {
                try { process(claimed); } finally { slots.release(); }
            });
        } catch (RuntimeException error) {
            try { if (batch != null) store.retry(batch); }
            catch (RuntimeException retryError) { log.warn("长期记忆任务重试登记失败: {}", retryError.getClass().getSimpleName()); }
            finally { slots.release(); }
            log.warn("长期记忆任务领取失败: {}", error.getClass().getSimpleName());
        }
    }

    private void process(UserMemoryStore.Batch batch) {
        Observation observation = Observation.start("user_memory.extract", observations);
        try (var scope = observation.openScope()) {
            observation.highCardinalityKeyValue("langfuse.session.id", batch.userId() + "_" + batch.sessionId());
            observation.lowCardinalityKeyValue("user_memory.turn_count", Integer.toString(batch.turns().size()));
            var candidates = extractor.extract(batch);
            observation.lowCardinalityKeyValue("user_memory.candidate_count", Integer.toString(candidates.size()));
            if (captureMemoryDetails) {
                String details = candidates.stream().filter(java.util.Objects::nonNull).map(item -> traceText(item.key() + ": " + item.content()))
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (!details.isEmpty()) observation.highCardinalityKeyValue("user_memory.candidates", details);
            }
            boolean applied = store.apply(batch, candidates);
            observation.lowCardinalityKeyValue("user_memory.result", applied ? "applied" : "superseded");
        } catch (RuntimeException error) {
            observation.error(new IllegalStateException(error.getClass().getSimpleName()));
            try { store.retry(batch); } catch (RuntimeException retryError) {
                log.warn("长期记忆任务重试登记失败: {}", retryError.getClass().getSimpleName());
            }
            log.warn("长期记忆提取失败: {}", error.getClass().getSimpleName());
        } finally {
            observation.stop();
        }
    }

    private static String traceText(String text) {
        if (text == null) return "";
        if (UserMemoryStore.isSensitive(text)) return "[已隐藏可能包含敏感信息的内容]";
        return text.length() <= 8000 ? text : text.substring(0, 8000) + "…[truncated]";
    }

    @Override
    public void close() { executor.shutdownNow(); }
}
