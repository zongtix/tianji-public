package com.tianji.remark.task;

import com.tianji.remark.utils.LikeCacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 点赞异步管线的监控与写入保护，独立定时采样 Redis/NOTE 队列状态，不承担落库或计数修复。
 * XLEN 是 Stream 正文数量（含新消息、pending、已确认残留），pending 是已投递未确认数，两者不能等同。
 * 日志关注积压、最旧待办延迟、非 READY、消费耗时和残留；LikeCacheService 还查询本类决定是否拒绝新写入。
 * 拒写是背压：数据库/MQ 消费变慢时限制日志继续增长，而不是通过裁剪 Stream 丢掉未落库操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeMetricsTask {
    private static final String NOTE_QUEUE = "remark.note.liked.times.queue";
    private static final long LAG_ALERT_REPEAT_MS = 60000;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final StringRedisTemplate redisTemplate;
    private final AmqpAdmin amqpAdmin;
    private final LikeWriteBehindTask writeBehindTask;

    @Value("${tj.like.metrics.log-interval-ms:30000}")
    private long logIntervalMs = 30000;
    @Value("${tj.like.alert.pending-threshold:1000}")
    private long pendingThreshold = 1000;
    @Value("${tj.like.alert.lag-ms-threshold:60000}")
    private long lagThresholdMs = 60000;
    @Value("${tj.like.alert.ready-stale-ms:30000}")
    private long readyStaleMs = 30000;
    @Value("${tj.like.alert.residual-stale-ms:300000}")
    private long residualStaleMs = 300000;
    @Value("${tj.like.alert.note-queue-threshold:1000}")
    private long noteQueueThreshold = 1000;
    @Value("${tj.like.alert.worker-busy-ms:30000}")
    private long workerBusyThresholdMs = 30000;
    @Value("${tj.like.backlog.high-watermark:50000}")
    private long highWatermark = 50000;
    @Value("${tj.like.backlog.sample-stale-ms:2000}")
    private long sampleStaleMs = 2000;

    private long lastInfoAt;
    private long nonReadySince;
    private long residualSince;
    private boolean pendingAlerted;
    private boolean lagAlerted;
    private long lastLagAlertAt;
    private boolean readyAlerted;
    private boolean residualAlerted;
    private boolean noteQueueAlerted;
    private boolean redisErrorAlerted;
    private boolean mqErrorAlerted;
    private long lastNoteQueueCount = -1;
    private volatile long lastSampleAt;
    private volatile boolean backlogRejecting = true;
    private boolean backlogAlerted;
    private boolean workerBusyAlerted;
    private final AtomicBoolean staleSampleAlerted = new AtomicBoolean();
    private final AtomicBoolean fallbackErrorAlerted = new AtomicBoolean();

    /**
     * 请求线程读取最近采样结果；采样过期时同步核验 XLEN，成功就更新积压状态，失败才拒写。
     * volatile 使请求线程能看到采样线程的新值，AtomicBoolean 用于一次性告警；并非逐请求都扫描日志。
     * 采样水位是保护机制，允许采样间隔内新增日志，并不是 Lua 中严格限制队列长度的原子上限。
     */
    public boolean rejectingWrites() {
        if (lastSampleAt == 0 || System.currentTimeMillis() - lastSampleAt > sampleStaleMs) {
            if (staleSampleAlerted.compareAndSet(false, true)) {
                log.error("ALERT 点赞积压监控状态过期，写入前实时核验 Stream 长度");
            }
            try {
                Long size = redisTemplate.opsForStream().size(LikeCacheKeys.CHANGE_STREAM);
                if (size == null) {
                    throw new DataAccessResourceFailureException("点赞 Stream 长度未知");
                }
                updateBacklogState(size, System.currentTimeMillis());
                fallbackErrorAlerted.set(false);
            } catch (DataAccessException e) {
                if (fallbackErrorAlerted.compareAndSet(false, true)) {
                    log.error("ALERT 点赞积压实时核验失败，拒绝新写入", e);
                }
                return true;
            }
        }
        return backlogRejecting;
    }

    @Scheduled(fixedDelayString = "${tj.like.metrics.poll-ms:1000}")
    public void sample() {
        sampleAt(System.currentTimeMillis());
    }

    /**
     * 采集共享缓存状态与日志待办，区分未消费新消息、pending 和已 ACK 但未 XDEL 的残留。
     * 用最旧待办 Stream ID 的毫秒部分估算等待时间；结果用于告警，不表示某条请求精确的全链路延迟。
     */
    void sampleAt(long now) {
        final String ready;
        final long xlen;
        final long pending;
        final long lagMs;
        final boolean residual;
        try {
            ready = redisTemplate.opsForValue().get(LikeCacheKeys.READY_KEY);
            StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
            Long size = streams.size(LikeCacheKeys.CHANGE_STREAM);
            if (size == null) {
                throw new DataAccessResourceFailureException("点赞 Stream 长度未知");
            }
            xlen = size;
            updateBacklogState(xlen, now);
            if (xlen == 0) {
                PendingMessagesSummary summary = "READY".equals(ready)
                        ? streams.pending(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP) : null;
                pending = summary == null ? 0 : summary.getTotalPendingMessages();
                lagMs = pending == 0 ? 0 : Math.max(0, now - streamTime(summary.minMessageId()));
                residual = false;
            } else {
                PendingMessagesSummary summary = streams.pending(
                        LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP);
                pending = summary.getTotalPendingMessages();
                String oldest = pending > 0 ? summary.minMessageId() : null;
                // filter 找到本模块消费组，findFirst 返回 Optional，orElse(null) 表示消费组缺失。
                // lastDeliveredId 后的第一条正文是尚未投递的新消息；要与最早 pending 一起考虑才能找到最旧待办。
                StreamInfo.XInfoGroup group = streams.groups(LikeCacheKeys.CHANGE_STREAM).stream()
                        .filter(g -> LikeCacheKeys.CHANGE_GROUP.equals(g.groupName()))
                        .findFirst().orElse(null);
                if (group == null) {
                    oldest = streams.info(LikeCacheKeys.CHANGE_STREAM).firstEntryId();
                    residual = false;
                } else {
                    List<MapRecord<String, Object, Object>> fresh = streams.range(
                            LikeCacheKeys.CHANGE_STREAM,
                            Range.from(Range.Bound.exclusive(group.lastDeliveredId()))
                                    .to(Range.Bound.unbounded()), Limit.limit().count(1));
                    if (fresh != null && !fresh.isEmpty()) {
                        String freshId = fresh.get(0).getId().getValue();
                        if (oldest == null || compareIds(freshId, oldest) < 0) {
                            oldest = freshId;
                        }
                    }
                    residual = pending == 0 && (fresh == null || fresh.isEmpty());
                }
                lagMs = oldest == null ? 0 : Math.max(0, now - streamTime(oldest));
            }
        } catch (DataAccessException e) {
            if (!redisErrorAlerted) {
                log.error("ALERT 点赞监控读取 Redis 失败", e);
                redisErrorAlerted = true;
            }
            return;
        }
        redisErrorAlerted = false;
        staleSampleAlerted.set(false);
        fallbackErrorAlerted.set(false);

        if (pending > pendingThreshold && !pendingAlerted) {
            log.error("ALERT 点赞积压超阈值 pending={} 阈值={}", pending, pendingThreshold);
            pendingAlerted = true;
        } else if (pending <= pendingThreshold) {
            pendingAlerted = false;
        }
        if (lagMs > lagThresholdMs) {
            if (!lagAlerted || now - lastLagAlertAt >= LAG_ALERT_REPEAT_MS) {
                log.error("ALERT 点赞落库延迟超阈值 lagMs={} 阈值={}", lagMs, lagThresholdMs);
                lagAlerted = true;
                lastLagAlertAt = now;
            }
        } else {
            lagAlerted = false;
            lastLagAlertAt = 0;
        }
        long workerBusyMs = writeBehindTask.activeBatchMillis(now);
        if (workerBusyMs > workerBusyThresholdMs && !workerBusyAlerted) {
            log.error("ALERT 点赞消费批次处理超时 busyMs={} 阈值={}", workerBusyMs,
                    workerBusyThresholdMs);
            workerBusyAlerted = true;
        } else if (workerBusyMs <= workerBusyThresholdMs) {
            workerBusyAlerted = false;
        }
        if (!"READY".equals(ready)) {
            if (nonReadySince == 0) {
                nonReadySince = now;
            }
            if (now - nonReadySince >= readyStaleMs && !readyAlerted) {
                log.error("ALERT 点赞缓存非 READY 持续 {}ms state={}", now - nonReadySince,
                        ready == null ? "MISSING" : ready);
                readyAlerted = true;
            }
        } else {
            nonReadySince = 0;
            readyAlerted = false;
        }
        if (residual && xlen > 0) {
            if (residualSince == 0) {
                residualSince = now;
            }
            if (now - residualSince >= residualStaleMs && !residualAlerted) {
                log.error("ALERT 点赞 Stream 存在已确认残留 xlen={} pending=0 持续={}ms",
                        xlen, now - residualSince);
                residualAlerted = true;
            }
        } else {
            residualSince = 0;
            residualAlerted = false;
        }

        if (lastInfoAt == 0 || now - lastInfoAt >= logIntervalMs) {
            lastInfoAt = now;
            sampleNoteQueue();
            long lastSuccess = writeBehindTask.lastSuccessAtMillis();
            log.info("like.metrics xlen={} pending={} lagMs={} workerBusyMs={} failCount={} lastSuccessAt={} ready={} noteQueue={}",
                    xlen, pending, lagMs, workerBusyMs, writeBehindTask.failureCount(),
                    lastSuccess == 0 ? "-" : TIME_FORMAT.format(Instant.ofEpochMilli(lastSuccess)),
                    ready == null ? "MISSING" : ready, lastNoteQueueCount);
        }
    }

    /**
     * 超过高水位拒写，低于其 80% 才恢复，留出缓冲区，避免在阈值附近不停切换。
     * synchronized 保护采样线程/请求实时核验的共同更新；时间比较避免旧采样覆盖新状态。
     */
    private synchronized void updateBacklogState(long xlen, long observedAt) {
        if (observedAt < lastSampleAt) {
            return;
        }
        if (xlen > highWatermark) {
            backlogRejecting = true;
            if (!backlogAlerted) {
                log.error("ALERT 点赞积压拒写 xlen={} 阈值={}", xlen, highWatermark);
                backlogAlerted = true;
            }
        } else if (xlen < highWatermark * 4 / 5) {
            backlogRejecting = false;
            backlogAlerted = false;
        }
        lastSampleAt = observedAt;
    }

    private void sampleNoteQueue() {
        try {
            Properties properties = amqpAdmin.getQueueProperties(NOTE_QUEUE);
            if (properties == null) {
                lastNoteQueueCount = -1;
                if (!mqErrorAlerted) {
                    log.error("ALERT NOTE 点赞兜底队列不存在 queue={}", NOTE_QUEUE);
                    mqErrorAlerted = true;
                }
                return;
            }
            Object count = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            lastNoteQueueCount = count instanceof Number ? ((Number) count).longValue() : -1;
            mqErrorAlerted = false;
            if (lastNoteQueueCount > noteQueueThreshold && !noteQueueAlerted) {
                log.error("ALERT NOTE 点赞兜底队列积压 messages={} 阈值={}",
                        lastNoteQueueCount, noteQueueThreshold);
                noteQueueAlerted = true;
            } else if (lastNoteQueueCount <= noteQueueThreshold) {
                noteQueueAlerted = false;
            }
        } catch (AmqpException e) {
            lastNoteQueueCount = -1;
            if (!mqErrorAlerted) {
                log.error("ALERT 点赞监控读取 NOTE 队列失败", e);
                mqErrorAlerted = true;
            }
        }
    }

    private long streamTime(String id) {
        return Long.parseLong(id.substring(0, id.indexOf('-')));
    }

    /**
     * Stream ID 为“毫秒时间-同毫秒序号”，按两段数值比较；直接比较字符串会误判不同位数的序号。
     */
    private int compareIds(String left, String right) {
        long leftTime = streamTime(left);
        long rightTime = streamTime(right);
        int timestampOrder = Long.compare(leftTime, rightTime);
        return timestampOrder != 0 ? timestampOrder : Long.compare(
                Long.parseLong(left.substring(left.indexOf('-') + 1)),
                Long.parseLong(right.substring(right.indexOf('-') + 1)));
    }
}
