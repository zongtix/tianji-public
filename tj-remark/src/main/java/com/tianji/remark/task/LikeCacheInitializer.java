package com.tianji.remark.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.domain.dto.LikeChange;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.LikeTargetService;
import com.tianji.remark.exception.LikeServiceUnavailableException;
import com.tianji.remark.mq.LikeTimesPublisher;
import com.tianji.remark.exception.LikePublishRetryableException;
import com.tianji.remark.exception.LikePublishUnroutableException;
import com.tianji.remark.utils.LikeCacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * 启动/恢复协调器（ApplicationRunner）：把数据库已落库关系重建为 Redis 的用户 Set 与数量 ZSet。
 * 顺序为初始化锁 → BUILDING 停写 → 消费锁 → 排空旧变更 → 清理派生缓存 → 回填 MySQL → 补发 → READY。
 * 必须先排空 Stream：直接以旧 MySQL 重建会丢掉已接受但尚未落库的点赞/取消。
 * 保留版本 Hash、Stream/消费组和补发 Hash，使缓存重建不重置下游拒绝旧消息的基线。
 * 已有 READY 时直接复用缓存；本类并非每次进程启动都无条件清空，也不会自行检测并修复所有缓存损坏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeCacheInitializer implements ApplicationRunner {
    private static final int BATCH_SIZE = 500;
    private static final int MAX_DRAIN_ROUNDS = 10000;
    private static final String INITIALIZE_LOCK = LikeCacheKeys.INITIALIZE_LOCK;
    private static final DefaultRedisScript<Long> REMOVE_RETRY_SCRIPT = removeRetryScript();
    private static final DefaultRedisScript<List> SNAPSHOT_SCRIPT = snapshotScript();

    private final LikedRecordMapper likedRecordMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final LikeWriteBehindTask writeBehindTask;
    private final LikeTimesPublisher likeTimesPublisher;
    private final LikeTargetService targetService;

    /**
     * Spring 启动后调用，无需 HTTP 请求；多实例竞争初始化锁，只有非 READY 状态才恢复。
     * 先设 BUILDING，再取得 WORKER_LOCK 等待正在运行的批次结束，随后排空日志并从一致的数据库关系回填。
     * 发生异常时不推进 READY，避免请求读取半成品；MQ 补发有持久化标记，可在 READY 后继续重试。
     */
    @Override
    public void run(ApplicationArguments args) {
        RLock lock = redissonClient.getLock(INITIALIZE_LOCK);
        lock.lock();
        try {
            String state = redisTemplate.opsForValue().get(LikeCacheKeys.READY_KEY);
            if ("READY".equals(state)) {
                return;
            }

            boolean hadCacheData = hasLikeDataKeys();
            redisTemplate.opsForValue().set(LikeCacheKeys.READY_KEY, "BUILDING");
            RLock workerLock = redissonClient.getLock(LikeCacheKeys.WORKER_LOCK);
            workerLock.lock();
            try {
                // 第 1 步：保留/建立消费组，再把未落库日志写回 MySQL。
                // 第 2 步：仅清理可重建的数据并回填关系/计数；版本、原日志与补发标记不随之清空。
                // 第 3 步：尝试通知下游，然后开放请求；开放并不保证每条补发消息已被下游消费。
                createChangeStreamGroup();
                Map<String, LikeChange> failedSnapshots = new LinkedHashMap<>();
                drainChanges(failedSnapshots);
                clearLikeNamespace();
                loadExistingRecords();
                resendFailedSnapshots(failedSnapshots);
                redisTemplate.opsForValue().set(LikeCacheKeys.READY_KEY, "READY");
                log.info("点赞 Redis 缓存初始化完成，先前缓存数据存在={}", hadCacheData);
            } finally {
                if (workerLock.isHeldByCurrentThread()) {
                    workerLock.unlock();
                }
            }
        } catch (DataAccessException e) {
            log.error("ALERT 点赞缓存初始化读取 Redis 失败，READY 不推进", e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在停写与消费锁保护下循环排空，直到任务确认达到可重建状态，最多尝试固定轮次。
     * 数据库/依赖异常或超限中断恢复，保持 BUILDING，不能用失败后的部分数据开放读写。
     */
    private void drainChanges(Map<String, LikeChange> failedSnapshots) {
        for (int round = 1; round <= MAX_DRAIN_ROUNDS; round++) {
            try {
                writeBehindTask.drainOneStreamBatch(failedSnapshots);
            } catch (DataAccessException | TransactionException | AmqpException |
                     LikePublishRetryableException | LikePublishUnroutableException | LikeServiceUnavailableException e) {
                log.error("点赞 Stream 恢复排空失败，READY 保持 BUILDING", e);
                throw new IllegalStateException("点赞 Stream 恢复排空失败", e);
            }
            if (writeBehindTask.isDrainComplete()) {
                return;
            }
        }
        log.error("点赞 Stream 排空超过轮次上限 {}，READY 保持 BUILDING", MAX_DRAIN_ROUNDS);
        throw new IllegalStateException("点赞 Stream 排空超过轮次上限");
    }

    /**
     * 重建完成后立即尝试发布恢复期间失败的对象（最多三次）。
     * 内存 Map 只判断本轮是否有失败；发布所需待办从持久化的 RETRY_SNAPSHOTS Hash 读取。
     */
    private void resendFailedSnapshots(Map<String, LikeChange> failedSnapshots) {
        if (failedSnapshots.isEmpty()) {
            return;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                publishRetrySnapshots(true);
                return;
            } catch (AmqpException | LikePublishRetryableException | LikePublishUnroutableException e) {
                writeBehindTask.recordPublishFailure();
                if (attempt == 3) {
                    log.error("ALERT 点赞恢复快照补发失败，业务对象数={}，将在 READY 后继续重试",
                            failedSnapshots.size(), e);
                }
            }
        }
    }

    /**
     * READY 后周期性扫描持久化补发标记（默认间隔 10 秒），进程重启也能继续未完成的通知。
     */
    @Scheduled(fixedDelayString = "${tj.like.recovery-retry-ms:10000}")
    public void retryFailedSnapshots() {
        if (!"READY".equals(redisTemplate.opsForValue().get(LikeCacheKeys.READY_KEY))) {
            return;
        }
        try {
            publishRetrySnapshots(false);
        } catch (DataAccessException | LikeServiceUnavailableException e) {
            log.error("ALERT 点赞恢复快照读取或目标校验失败", e);
        } catch (AmqpException | LikePublishRetryableException | LikePublishUnroutableException e) {
            writeBehindTask.recordPublishFailure();
            log.error("ALERT 点赞恢复快照仍未补发成功", e);
        }
    }

    /**
     * 从 Hash 读取最多一批对象标记 → 按类型检查存在性 → 原子读取当前数量/版本 → 发布 → 条件删除标记。
     * Hash 值是随机 token 而非旧数量；补发读取 freshSnapshot，避免重发过时数量覆盖较新的业务状态。
     * 发布成功后以字段值比较删除，保护读取之后并发产生的新补发标记。
     */
    private void publishRetrySnapshots(boolean allowBuilding) {
        Map<Object, Object> stored = redisTemplate.opsForHash().entries(LikeCacheKeys.RETRY_SNAPSHOTS);
        if (stored == null || stored.isEmpty()) {
            return;
        }
        List<Map.Entry<Object, Object>> batch = new ArrayList<>(Math.min(BATCH_SIZE, stored.size()));
        List<LikeChange> targets = new ArrayList<>(Math.min(BATCH_SIZE, stored.size()));
        for (Map.Entry<Object, Object> entry : stored.entrySet()) {
            String[] target = entry.getKey().toString().split(":", 2);
            if (target.length != 2) {
                throw new IllegalStateException("点赞补发标记格式错误：" + entry.getKey());
            }
            LikeChange snapshot = new LikeChange();
            snapshot.setBizType(target[0]);
            snapshot.setBizId(Long.valueOf(target[1]));
            targets.add(snapshot);
            batch.add(entry);
            if (batch.size() == BATCH_SIZE) {
                break;
            }
        }
        // 结果为 Map<bizType, List<bizId>>；existingTargets 则为 Set<类型:对象ID>，用于过滤已删除对象。
        // fresh 是新的计数快照列表，区别于 targets 中只有定位字段的临时 DTO。
        Map<String, List<Long>> idsByType = targets.stream()
                .collect(Collectors.groupingBy(LikeChange::getBizType,
                        Collectors.mapping(LikeChange::getBizId, Collectors.toList())));
        Set<String> existingTargets = new HashSet<>();
        for (Map.Entry<String, List<Long>> entry : idsByType.entrySet()) {
            Set<Long> existing = targetService.existingIds(entry.getKey(), entry.getValue());
            List<Long> missing = entry.getValue().stream()
                    .filter(id -> !existing.contains(id)).collect(Collectors.toList());
            targetService.removeMissingTargets(entry.getKey(), missing);
            for (Long id : existing) {
                existingTargets.add(entry.getKey() + ":" + id);
            }
        }
        List<LikeChange> fresh = new ArrayList<>(targets.size());
        for (LikeChange target : targets) {
            if (existingTargets.contains(target.getBizType() + ":" + target.getBizId())) {
                fresh.add(freshSnapshot(target, allowBuilding));
            }
        }
        likeTimesPublisher.publish(fresh);
        writeBehindTask.recordPublishSuccess();
        for (Map.Entry<Object, Object> entry : batch) {
            // 发布确认后仅删掉本次读到的标记；并发产生的新标记留给下次补发。
            redisTemplate.execute(REMOVE_RETRY_SCRIPT,
                    Collections.singletonList(LikeCacheKeys.RETRY_SNAPSHOTS),
                    entry.getKey().toString(), entry.getValue().toString());
        }
    }

    /**
     * 比较 Hash 当前值与本次读取 token，匹配才删除；比较和删除放在同一 Lua，避免两步间覆盖新任务。
     */
    private static DefaultRedisScript<Long> removeRetryScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] " +
                "then return redis.call('HDEL', KEYS[1], ARGV[1]) else return 0 end");
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<List> snapshotScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/query-like-snapshot.lua"));
        script.setResultType(List.class);
        return script;
    }

    /**
     * 用 query-like-snapshot.lua 同时取数量与版本，返回 [count, version] 再组装成消息快照。
     * allowBuilding 仅恢复期间允许读取；READY 后的定时补发不能读取正在重建的数据。
     * Lua 保证这两个值不会混用两个时点，但不保证之后 MQ 到达顺序，下游仍需比较版本。
     */
    private LikeChange freshSnapshot(LikeChange original, boolean allowBuilding) {
        List<?> snapshot = redisTemplate.execute(SNAPSHOT_SCRIPT, Arrays.asList(
                LikeCacheKeys.countSortedSet(original.getBizType()),
                LikeCacheKeys.versionHash(original.getBizType()), LikeCacheKeys.READY_KEY),
                original.getBizId().toString(), allowBuilding ? "1" : "0");
        if (snapshot == null || snapshot.size() != 2) {
            throw new LikeServiceUnavailableException("点赞缓存未就绪，暂不补发快照");
        }
        LikeChange result = new LikeChange();
        result.setBizType(original.getBizType());
        result.setBizId(original.getBizId());
        result.setLikedTimes(Integer.valueOf(snapshot.get(0).toString()));
        result.setVersion(Long.valueOf(snapshot.get(1).toString()));
        return result;
    }

    /**
     * 按主键游标每次读取 500 条历史关系，校验目标后批量回填 Set 并按每条关系累计 ZSet 数量。
     * LambdaQueryWrapper<LikedRecord> 用 getter 引用生成字段条件，仅查回填所需列；gt(id,lastId)+升序+LIMIT
     * 避免深页 OFFSET 重复跳过大量记录，也控制内存。此时写入已停且日志已排空，数据库是回填依据。
     */
    private void loadExistingRecords() {
        Long lastId = null;
        while (true) {
            LambdaQueryWrapper<LikedRecord> query = new LambdaQueryWrapper<LikedRecord>()
                    .select(LikedRecord::getId, LikedRecord::getUserId,
                            LikedRecord::getBizId, LikedRecord::getBizType)
                    .orderByAsc(LikedRecord::getId)
                    .last("LIMIT " + BATCH_SIZE);
            if (lastId != null) {
                query.gt(LikedRecord::getId, lastId);
            }

            List<LikedRecord> records = likedRecordMapper.selectList(query);
            if (records.isEmpty()) {
                return;
            }

            // 把本批关系分组为 Map<业务类型, 对象ID列表>，跨服务批量确认对象存在。
            // Set<类型:ID> 让 validRecords 可直接按对象过滤；同一对象的多个用户关系仍保留，用于累加总数。
            Map<String, List<Long>> idsByType = records.stream()
                    .collect(Collectors.groupingBy(LikedRecord::getBizType,
                            Collectors.mapping(LikedRecord::getBizId, Collectors.toList())));
            Set<String> existingTargets = new HashSet<>();
            for (Map.Entry<String, List<Long>> entry : idsByType.entrySet()) {
                Set<Long> existing = targetService.existingIds(entry.getKey(), entry.getValue());
                List<Long> missing = entry.getValue().stream().distinct()
                        .filter(id -> !existing.contains(id)).collect(Collectors.toList());
                targetService.removeMissingTargets(entry.getKey(), missing);
                for (Long id : existing) {
                    existingTargets.add(entry.getKey() + ":" + id);
                }
            }
            List<LikedRecord> validRecords = records.stream()
                    .filter(record -> existingTargets.contains(record.getBizType() + ":" + record.getBizId()))
                    .collect(Collectors.toList());

            // 用 pipeline 批量回填关系 Set 和点赞数 ZSet，避免初始化期间逐条往返 Redis。
            // pipeline 减少每条 SADD/ZINCRBY 的网络等待，但不会把整个批次变成 Redis 原子事务。
            // 恢复过程靠停写和两个锁隔离；只有整次回填成功才设置 READY，避免暴露中间计数。
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (LikedRecord record : validRecords) {
                    byte[] userSet = bytes(LikeCacheKeys.userBizSet(record.getBizType(), record.getUserId()));
                    byte[] bizId = bytes(record.getBizId().toString());
                    byte[] countSet = bytes(LikeCacheKeys.countSortedSet(record.getBizType()));
                    connection.sAdd(userSet, bizId);
                    connection.zIncrBy(countSet, 1D, bizId);
                }
                return null;
            });
            lastId = records.get(records.size() - 1).getId();
        }
    }

    /**
     * 已有 Stream 时保留记录与现有消费组；新建消费组从 0-0 开始，不跳过历史变更。
     * 没有 Stream 时先写初始化占位、创建消费组，再删占位，确保“空但可消费”的日志结构存在。
     */
    private void createChangeStreamGroup() {
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        if (Boolean.TRUE.equals(redisTemplate.hasKey(LikeCacheKeys.CHANGE_STREAM))) {
            if (streams.groups(LikeCacheKeys.CHANGE_STREAM).stream()
                    .noneMatch(group -> LikeCacheKeys.CHANGE_GROUP.equals(group.groupName()))) {
                streams.createGroup(LikeCacheKeys.CHANGE_STREAM, ReadOffset.from("0-0"), LikeCacheKeys.CHANGE_GROUP);
            }
            return;
        }
        RecordId markerId = streams.add(LikeCacheKeys.CHANGE_STREAM,
                Collections.singletonMap("initializer", "ready"));
        streams.createGroup(LikeCacheKeys.CHANGE_STREAM, ReadOffset.latest(), LikeCacheKeys.CHANGE_GROUP);
        // 初始化标记不代表用户操作，创建消费组后立即移除，避免它进入业务处理流程。
        streams.delete(LikeCacheKeys.CHANGE_STREAM, markerId);
    }

    /**
     * 通过 SCAN 分批清理点赞派生数据，保留 READY、变更 Stream、补发 Hash 和版本 Hash。
     * 若把 Stream 清掉，可能丢失尚未落库日志；若把版本清零，旧 MQ 消息可能再次被下游接受。
     */
    private void clearLikeNamespace() {
        byte[] readyKey = bytes(LikeCacheKeys.READY_KEY);
        byte[] changeStream = bytes(LikeCacheKeys.CHANGE_STREAM);
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(LikeCacheKeys.PREFIX + "*")
                    .count(1000)
                    .build();
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.scan(options)) {
                List<byte[]> keys = new ArrayList<>(BATCH_SIZE);
                while (cursor.hasNext()) {
                    byte[] key = cursor.next();
                    // Stream 中可能还有未落库的变更，重建缓存时必须保留。
                    if (Arrays.equals(readyKey, key) || Arrays.equals(changeStream, key) ||
                            Arrays.equals(bytes(LikeCacheKeys.RETRY_SNAPSHOTS), key) ||
                            new String(key, StandardCharsets.UTF_8).startsWith(LikeCacheKeys.PREFIX + "version:")) {
                        // 版本 Hash 是下游拒绝旧消息的单调基线，重建计数时不能重置。
                        continue;
                    }
                    keys.add(key);
                    if (keys.size() == BATCH_SIZE) {
                        connection.del(keys.toArray(new byte[0][]));
                        keys.clear();
                    }
                }
                if (!keys.isEmpty()) {
                    connection.del(keys.toArray(new byte[0][]));
                }
            }
            return null;
        });
    }

    /**
     * 只检查是否曾有用户 Set/计数 ZSet，用于初始化日志；此返回值不是跳过排空或重建的条件。
     */
    private boolean hasLikeDataKeys() {
        Boolean found = redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(LikeCacheKeys.PREFIX + "*")
                    .count(1000)
                    .build();
            byte[] readyKey = bytes(LikeCacheKeys.READY_KEY);
            byte[] changeStream = bytes(LikeCacheKeys.CHANGE_STREAM);
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    byte[] key = cursor.next();
                    if (Arrays.equals(readyKey, key) || Arrays.equals(changeStream, key)) {
                        continue;
                    }
                    String name = new String(key, StandardCharsets.UTF_8);
                    if (name.startsWith(LikeCacheKeys.PREFIX + "user:") ||
                            name.startsWith(LikeCacheKeys.PREFIX + "count:")) {
                        return true;
                    }
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(found);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
