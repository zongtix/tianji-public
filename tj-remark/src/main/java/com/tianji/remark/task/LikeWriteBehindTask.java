package com.tianji.remark.task;

import com.tianji.remark.domain.dto.LikeChange;
import com.tianji.remark.domain.enums.LikeBizType;
import com.tianji.remark.service.LikeChangePersistenceService;
import com.tianji.remark.service.LikeDeadLetterService;
import com.tianji.remark.exception.LikePublishRetryableException;
import com.tianji.remark.exception.LikeServiceUnavailableException;
import com.tianji.remark.exception.LikePublishUnroutableException;
import com.tianji.remark.mq.LikeTimesPublisher;
import com.tianji.remark.utils.LikeCacheKeys;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Range.Bound;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import java.nio.charset.StandardCharsets;

/**
 * 写后异步协调器：读取 Redis Stream → 批量关系事务 → 发布计数快照 → XACK → XDEL。
 * 定时任务默认每轮完成后间隔 1 秒（可配置），使用 Redisson WORKER_LOCK 让多实例串行处理。
 * 先处理消费组的 pending（已投递未确认），再读 fresh，防止旧点赞在较新取消落库后又插回关系。
 * 失败记录保持 pending，数据库写入与消息通知可重试；解析失败记录须存入数据库死信表后才确认。
 * 初始化排空也复用此类，但允许 MQ 失败后先持久化补发标记，关系落库完成后继续恢复缓存。
 * 这是一条最终一致的管线，不是把 Redis、MySQL 和 RabbitMQ 包进同一个事务。
 */
@Slf4j
@Component
public class LikeWriteBehindTask {
    private static final int STREAM_BATCH_SIZE = 500;
    private static final int DEADLETTER_BACKOFF_LIMIT = 1000;

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final LikeChangePersistenceService persistenceService;
    private final LikeTimesPublisher likeTimesPublisher;
    private final LikeDeadLetterService deadLetterService;
    private final PublishBackoff backoff;
    private final long backoffBaseMs;
    private final long backoffMaxMs;
    private final Map<String, PublishBackoff> deadLetterBackoffs =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicLong failureCount = new AtomicLong();
    private volatile long lastSuccessAtMillis;
    private volatile long activeBatchSinceMillis;
    // PEL 是消费组的未确认记录列表；cursor 只用于分页读取，不代表这些记录已经处理成功。
    private String pendingCursor = "0-0";
    private boolean lastFreshReadEmpty;
    private boolean recoveryOrphansClaimed;
    private boolean recoveryPendingSweepComplete;

    public LikeWriteBehindTask(StringRedisTemplate redisTemplate, RedissonClient redissonClient,
                               LikeChangePersistenceService persistenceService, LikeTimesPublisher likeTimesPublisher,
                               LikeDeadLetterService deadLetterService,
                               @Value("${tj.like.retry.backoff-base-ms:1000}") long backoffBaseMs,
                               @Value("${tj.like.retry.backoff-max-ms:60000}") long backoffMaxMs) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.persistenceService = persistenceService;
        this.likeTimesPublisher = likeTimesPublisher;
        this.deadLetterService = deadLetterService;
        this.backoff = new PublishBackoff(backoffBaseMs, backoffMaxMs);
        this.backoffBaseMs = backoffBaseMs;
        this.backoffMaxMs = backoffMaxMs;
    }

    /**
     * 正常运行的定时入口：退避未到期/缓存未就绪时跳过，否则尝试拿消费锁处理一轮。
     * tryLock 不等待，另一实例正在处理时本轮退出；Redisson 无显式租期的锁使用其自动续期机制。
     * 锁覆盖读日志、数据库提交、发送确认和 ACK，避免两个实例交叉应用同一关系的旧/新状态。
     */
    @Scheduled(fixedDelayString = "${tj.like.flush-interval-ms:1000}")
    public void flushChangesAndPublish() {
        if (backoff.shouldSkip(System.currentTimeMillis())) {
            return;
        }
        if (!"READY".equals(redisTemplate.opsForValue().get(LikeCacheKeys.READY_KEY))) {
            return;
        }

        // 多个 remark 实例可以同时运行，但同一时刻只让一个实例消费 Stream。
        RLock lock = redissonClient.getLock(LikeCacheKeys.WORKER_LOCK);
        if (!lock.tryLock()) {
            return;
        }
        try {
            activeBatchSinceMillis = System.currentTimeMillis();
            flushOneStreamBatch();
            backoff.onSuccess();
        } catch (LikePublishUnroutableException e) {
            backoff.onFailure(System.currentTimeMillis());
            log.error("点赞消息无法路由，管线暂停直到队列恢复：{}", e.getMessage());
        } catch (LikePublishRetryableException | LikeServiceUnavailableException | AmqpException | DataAccessException | TransactionException e) {
            backoff.onFailure(System.currentTimeMillis());
            log.warn("处理点赞缓存批次失败，将按退避策略重试", e);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } finally {
                activeBatchSinceMillis = 0;
            }
        }
    }

    public long failureCount() {
        return failureCount.get();
    }

    public long lastSuccessAtMillis() {
        return lastSuccessAtMillis;
    }

    long activeBatchMillis(long now) {
        long started = activeBatchSinceMillis;
        return started == 0 ? 0 : Math.max(0, now - started);
    }

    void recordPublishFailure() {
        failureCount.incrementAndGet();
    }

    void recordPublishSuccess() {
        lastSuccessAtMillis = System.currentTimeMillis();
    }

    private int flushOneStreamBatch() {
        return processOneStreamBatch(false, null);
    }

    /**
     * 缓存初始化在持有 WORKER_LOCK 且 BUILDING 时调用，返回本轮确认的记录数。
     * 先接管其他消费者遗留 pending，再恢复关系；failedSnapshots 收集补发对象，持久化依据仍是 Redis Hash。
     */
    public int drainOneStreamBatch(Map<String, LikeChange> failedSnapshots) {
        if (!recoveryOrphansClaimed) {
            claimOrphanPending();
            recoveryOrphansClaimed = true;
        }
        int acknowledged = processOneStreamBatch(true, failedSnapshots);
        return acknowledged == 0 && lastFreshReadEmpty
                ? acknowledgeMissingPending() : acknowledged;
    }

    /**
     * 检查恢复是否达到可重建缓存的状态：新消息读空，并核对 Stream 与 pending。
     * XACK 成功但 XDEL 失败会留下已确认正文；反过来正文缺失却仍有 PEL 引用是“幽灵 pending”。
     * 这两个状态不同，不能只用 XLEN==0 判断是否排空；无法死信的坏记录还需确认已完成遍历/接管。
     */
    public boolean isDrainComplete() {
        if (!lastFreshReadEmpty) {
            return false;
        }
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        Long length = streams.size(LikeCacheKeys.CHANGE_STREAM);
        long pendingCount = streams.pending(LikeCacheKeys.CHANGE_STREAM,
                LikeCacheKeys.CHANGE_GROUP).getTotalPendingMessages();
        // XACK 已完成但 XDEL 失败的条目不在 PEL 中；新消息读空后无需让它阻塞恢复。
        // 此处保留残留数据，待运维确认后清理；PEL 多于 Stream 时仍需先清理幽灵引用。
        if (length == null || length < pendingCount) {
            return false;
        }
        if (pendingCount == 0) {
            return true;
        }
        long verified = 0;
        String cursor = null;
        while (true) {
            PendingMessages page = pendingPage(cursor);
            for (PendingMessage message : page) {
                if ((!deadLetterBackoffs.containsKey(message.getIdAsString()) &&
                        !recoveryPendingSweepComplete) ||
                        !LikeCacheKeys.WORKER_NAME.equals(message.getConsumerName()) ||
                        streams.range(LikeCacheKeys.CHANGE_STREAM,
                                Range.closed(message.getIdAsString(), message.getIdAsString())).isEmpty()) {
                    return false;
                }
                verified++;
            }
            if (page.size() < STREAM_BATCH_SIZE) {
                return verified == pendingCount;
            }
            cursor = page.get(page.size() - 1).getIdAsString();
        }
    }

    /**
     * 按 Stream ID 的排他下界分页查看 PEL，最多 500 条；只查看元信息，不进行业务落库或 ACK。
     */
    private PendingMessages pendingPage(String afterId) {
        Range<String> range = afterId == null ? Range.unbounded()
                : Range.from(Bound.exclusive(afterId)).to(Bound.unbounded());
        return redisTemplate.opsForStream().pending(LikeCacheKeys.CHANGE_STREAM,
                LikeCacheKeys.CHANGE_GROUP, range, STREAM_BATCH_SIZE);
    }

    /**
     * 恢复时把其他 consumer 的未确认记录 XCLAIM 到固定消费者名，防止仅读取自己的 PEL 而遗漏。
     * 初始化期间已停写并持有消费锁，所以此处使用 Duration.ZERO，不等空闲超时。
     * 无法 claim 且原正文已缺失时只清理 PEL 引用；正文仍在却接管失败则中断恢复。
     */
    private void claimOrphanPending() {
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        String cursor = null;
        byte[] streamKey = LikeCacheKeys.CHANGE_STREAM.getBytes(StandardCharsets.UTF_8);
        while (true) {
            PendingMessages page = pendingPage(cursor);
            List<RecordId> foreignIds = new ArrayList<>();
            for (PendingMessage message : page) {
                if (!LikeCacheKeys.WORKER_NAME.equals(message.getConsumerName())) {
                    foreignIds.add(message.getId());
                }
            }
            if (!foreignIds.isEmpty()) {
                List<ByteRecord> claimed = redisTemplate.execute((RedisCallback<List<ByteRecord>>) connection ->
                        connection.streamCommands().xClaim(streamKey, LikeCacheKeys.CHANGE_GROUP,
                                LikeCacheKeys.WORKER_NAME, Duration.ZERO, foreignIds.toArray(new RecordId[0])));
                Set<RecordId> claimedIds = new HashSet<>();
                if (claimed != null) {
                    for (ByteRecord record : claimed) {
                        claimedIds.add(record.getId());
                    }
                }
                for (RecordId id : foreignIds) {
                    if (!claimedIds.contains(id)) {
                        if (streams.range(LikeCacheKeys.CHANGE_STREAM,
                                Range.closed(id.getValue(), id.getValue())).isEmpty()) {
                            streams.acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP, id);
                        } else {
                            throw new IllegalStateException("无法接管点赞 Stream pending，streamId=" + id);
                        }
                    }
                }
            }
            if (page.size() < STREAM_BATCH_SIZE) {
                return;
            }
            cursor = page.get(page.size() - 1).getIdAsString();
        }
    }

    /**
     * 逐页清理“正文已丢失但 PEL 仍引用”的记录；只去掉无从重放的引用，不能恢复缺失载荷。
     */
    private int acknowledgeMissingPending() {
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        int cleaned = 0;
        String cursor = null;
        while (true) {
            PendingMessages page = pendingPage(cursor);
            for (PendingMessage message : page) {
                RecordId id = message.getId();
                if (streams.range(LikeCacheKeys.CHANGE_STREAM,
                        Range.closed(id.getValue(), id.getValue())).isEmpty()) {
                    streams.acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP, id);
                    cleaned++;
                }
            }
            if (page.size() < STREAM_BATCH_SIZE) {
                return cleaned;
            }
            cursor = page.get(page.size() - 1).getIdAsString();
        }
    }

    /**
     * 共用的一轮处理：先读旧 pending 并处理，若还有后续 pending 则本轮不读 fresh。
     * allowPublishFailure 只用于初始化恢复；正常模式任何有效记录的 DB/MQ/ACK 失败都重置 cursor 再重试。
     * 单次读取上限 500，一轮可能先处理 pending 又处理 fresh，不能把读取上限当作整轮固定总量。
     */
    private int processOneStreamBatch(boolean allowPublishFailure, Map<String, LikeChange> failedSnapshots) {
        try {
            List<MapRecord<String, Object, Object>> pending = readPendingBatch();
            int acknowledged = processBatch(pending, allowPublishFailure, failedSnapshots);
            if (hasLaterPending(pending)) {
                lastFreshReadEmpty = false;
                return acknowledged;
            }
            // 只有更早的有效 pending 都已落库并确认发布，才读取新消息。
            // 坏记录死信失败时仍留在 PEL，但扫到尾部后允许新消息继续前进。
            return acknowledged + processBatch(readFreshBatch(), allowPublishFailure, failedSnapshots);
        } catch (RuntimeException e) {
            // DB/MQ/ACK 失败的有效记录必须从 PEL 起点重试，不能被 cursor 越过。
            pendingCursor = "0-0";
            throw e;
        }
    }

    /**
     * ReadOffset.from(cursor) 读取该消费者已收到但未 ACK 的记录；到尾后从 0-0 再扫。
     * MapRecord 的 String 是 Stream Key 类型，两个 Object 是字段名/值类型；业务字段稍后在 toChange 中解析。
     */
    private List<MapRecord<String, Object, Object>> readPendingBatch() {
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        Consumer consumer = Consumer.from(LikeCacheKeys.CHANGE_GROUP, LikeCacheKeys.WORKER_NAME);
        List<MapRecord<String, Object, Object>> pending = streams.read(consumer,
                StreamReadOptions.empty().count(STREAM_BATCH_SIZE),
                StreamOffset.create(LikeCacheKeys.CHANGE_STREAM, ReadOffset.from(pendingCursor)));
        if ((pending == null || pending.isEmpty()) && !"0-0".equals(pendingCursor)) {
            if (recoveryOrphansClaimed) {
                recoveryPendingSweepComplete = true;
            }
            pendingCursor = "0-0";
            pending = streams.read(consumer,
                    StreamReadOptions.empty().count(STREAM_BATCH_SIZE),
                    StreamOffset.create(LikeCacheKeys.CHANGE_STREAM, ReadOffset.from(pendingCursor)));
        }
        if (pending != null && !pending.isEmpty()) {
            pendingCursor = pending.get(pending.size() - 1).getId().getValue();
        }
        return pending == null ? Collections.emptyList() : pending;
    }

    private boolean hasLaterPending(List<MapRecord<String, Object, Object>> pending) {
        if (pending.size() < STREAM_BATCH_SIZE) {
            if (recoveryOrphansClaimed) {
                recoveryPendingSweepComplete = true;
            }
            return false;
        }
        boolean hasLater = !pendingPage(pendingCursor).isEmpty();
        if (!hasLater && recoveryOrphansClaimed) {
            recoveryPendingSweepComplete = true;
        }
        return hasLater;
    }

    /**
     * ReadOffset.lastConsumed() 在消费组读取语义下获取未投递的新记录（对应 >），读取后进入 PEL。
     * 读取并不等于完成，只有数据库与发布流程满足条件后才 ACK。
     */
    private List<MapRecord<String, Object, Object>> readFreshBatch() {
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        Consumer consumer = Consumer.from(LikeCacheKeys.CHANGE_GROUP, LikeCacheKeys.WORKER_NAME);
        List<MapRecord<String, Object, Object>> fresh = streams.read(consumer,
                StreamReadOptions.empty().count(STREAM_BATCH_SIZE),
                StreamOffset.create(LikeCacheKeys.CHANGE_STREAM, ReadOffset.lastConsumed()));
        lastFreshReadEmpty = fresh == null || fresh.isEmpty();
        return fresh == null ? Collections.emptyList() : fresh;
    }

    /**
     * 一批日志的完整处理链，返回可确认记录的数量。
     * 1. 解析成 LikeChange，坏载荷先保存死信；只有可重放/已隔离项加入 ACK 列表。
     * 2. 调用独立 Spring Bean 的 persistBatch，待数据库事务成功提交。
     * 3. 正常模式等待消息确认；恢复模式发送失败时，先将每个对象的补发标记写入 Redis Hash。
     * 4. XACK 去掉 PEL 待办，XDEL 删除正文；任一前置步骤失败则原日志保留供重试。
     * 数据库与 MQ 没有原子提交，可能出现数据库成功/MQ部分成功，因此必须容忍重复写库和重复消息。
     */
    private int processBatch(List<MapRecord<String, Object, Object>> records, boolean allowPublishFailure,
                              Map<String, LikeChange> failedSnapshots) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        List<LikeChange> changes = new ArrayList<>(records.size());
        List<RecordId> recordIds = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            try {
                changes.add(toChange(record.getValue()));
                recordIds.add(record.getId());
            } catch (IllegalArgumentException e) {
                if (saveDeadLetter(record, e.getMessage())) {
                    recordIds.add(record.getId());
                }
            }
        }

        // 正常消费必须等 MQ 确认才 ACK；恢复排空已落库的变更可在重建后补发快照。
        List<LikeChange> snapshots;
        try {
            snapshots = persistenceService.persistBatch(changes);
        } catch (DataAccessException | TransactionException | LikeServiceUnavailableException e) {
            failureCount.incrementAndGet();
            throw e;
        }
        if (allowPublishFailure) {
            try {
                likeTimesPublisher.publish(snapshots);
                if (!snapshots.isEmpty()) {
                    recordPublishSuccess();
                }
            } catch (LikePublishRetryableException | LikePublishUnroutableException | AmqpException e) {
                recordPublishFailure();
                for (LikeChange snapshot : snapshots) {
                    // 必须先把补发标记存入 Redis，再 ACK 原 Stream；重启后仍能补发。
                    // 随机 token 标识这一次补发任务，Hash 在进程重启后仍可被初始化器读取。
                    // 任何标记写入失败会退出此批，不能提前 ACK；只记在内存 failedSnapshots 中无法承担重启恢复。
                    redisTemplate.opsForHash().put(LikeCacheKeys.RETRY_SNAPSHOTS,
                            snapshot.getBizType() + ":" + snapshot.getBizId(), UUID.randomUUID().toString());
                    failedSnapshots.put(snapshot.getBizType() + ":" + snapshot.getBizId(), snapshot);
                }
                log.error("恢复排空阶段点赞快照发布失败，落库已完成，等待重建后补发", e);
            }
        } else {
            try {
                likeTimesPublisher.publish(snapshots);
                if (!snapshots.isEmpty()) {
                    recordPublishSuccess();
                }
            } catch (LikePublishRetryableException | LikePublishUnroutableException | AmqpException e) {
                recordPublishFailure();
                throw e;
            }
        }
        if (!recordIds.isEmpty()) {
            // XACK 只移除消费组的未确认引用，不删除 Stream 正文；因此成功后还要 XDEL 回收内存。
            // 先 ACK 再删除，避免待处理日志被提前删除；两条命令之间仍可能留下已确认残留，由监控识别。
            streams.acknowledge(LikeCacheKeys.CHANGE_STREAM, LikeCacheKeys.CHANGE_GROUP,
                    recordIds.toArray(new RecordId[0]));
            // 已持久化并确认的 Stream 项不再需要保留，及时删除以控制 Redis 内存增长。
            streams.delete(LikeCacheKeys.CHANGE_STREAM, recordIds.toArray(new RecordId[0]));
        }
        return recordIds.size();
    }

    /**
     * 将 Stream 字符串字段转成强类型内部 DTO，并校验必填字段、状态、类型及数字格式。
     * 它不是前端表单校验：Stream 中可能有历史或异常载荷，不能直接交给 SQL/MQ。
     */
    private LikeChange toChange(Map<Object, Object> values) {
        String userId = required(values, "userId");
        String bizType = required(values, "bizType");
        String bizId = required(values, "bizId");
        String liked = required(values, "liked");
        String likedTimes = required(values, "likedTimes");
        String version = required(values, "version");
        if (!"1".equals(liked) && !"0".equals(liked)) {
            throw new IllegalArgumentException("liked非法");
        }
        try {
            LikeChange change = new LikeChange();
            change.setUserId(Long.valueOf(userId));
            change.setBizType(bizType);
            change.setBizId(Long.valueOf(bizId));
            change.setLiked("1".equals(liked));
            change.setLikedTimes(Integer.valueOf(likedTimes));
            change.setVersion(Long.valueOf(version));
            try {
                LikeBizType.valueOf(bizType);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("bizType非法", e);
            }
            return change;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数字非法", e);
        }
    }

    private String required(Map<Object, Object> values, String key) {
        String result = value(values, key);
        if (result == null || result.isEmpty()) {
            throw new IllegalArgumentException("缺字段：" + key);
        }
        return result;
    }

    /**
     * 单条坏日志单独退避，防止死信表故障反复占用资源；成功隔离返回 true，允许该 ID ACK。
     * LinkedHashMap 记录插入次序，达到上限只淘汰最早的内存退避状态，原 Stream/PEL 不删除。
     */
    private boolean saveDeadLetter(MapRecord<String, Object, Object> record, String reason) {
        String streamId = record.getId().getValue();
        PublishBackoff retry;
        synchronized (deadLetterBackoffs) {
            retry = deadLetterBackoffs.computeIfAbsent(streamId,
                    ignored -> new PublishBackoff(backoffBaseMs, backoffMaxMs));
            if (deadLetterBackoffs.size() > DEADLETTER_BACKOFF_LIMIT) {
                // 最旧的失败项会继续留在 Stream/PEL；只丢内存退避状态，下轮仍可重试。
                String oldest = deadLetterBackoffs.keySet().iterator().next();
                deadLetterBackoffs.remove(oldest);
            }
        }
        long now = System.currentTimeMillis();
        if (retry.shouldSkip(now)) {
            return false;
        }
        try {
            deadLetterService.save(record, reason);
            deadLetterBackoffs.remove(streamId);
            return true;
        } catch (JsonProcessingException | DataAccessException e) {
            retry.onFailure(now);
            // 死信库持续故障时保留原记录，并在日志里留下定位所需的原文。
            log.error("ALERT 点赞死信写入失败，原 Stream 记录保留，streamId={}，reason={}，payload={}",
                    streamId, reason, record.getValue(), e);
            return false;
        }
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

}
