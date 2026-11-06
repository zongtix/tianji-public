package com.tianji.remark.service;

import com.tianji.remark.domain.dto.LikeChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis Stream → MySQL 的批量持久化层，由 LikeWriteBehindTask 通过 Spring Bean 调用。
 * 按用户关系和业务对象分别保留批内最大版本，再校验目标，批量插入点赞/删除取消关系。
 * 返回 List<LikeChange> 是每个对象的最新数量快照，调用方等数据库事务提交后再交给 LikeTimesPublisher。
 * MQ/ACK 不在本类的数据库事务内；发送失败时保留 Stream 待重试，唯一索引与状态式删除支持重做。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeChangePersistenceService {
    private static final String INSERT_LIKE_SQL =
            "INSERT INTO liked_record (user_id, biz_id, biz_type) VALUES ";
    private static final String DELETE_LIKE_SQL =
            "DELETE FROM liked_record WHERE (user_id, biz_id, biz_type) IN (";
    private final JdbcTemplate jdbcTemplate;
    private final LikeTargetService targetService;

    /**
     * 处理一批变更，并返回可发布的对象计数快照。
     * 流程：双维度去重 → 批量校验目标并清理无效关系 → 分出点赞/取消 → 批量写库 → 返回快照。
     * 事务必须让本批数据库清理、INSERT 和 DELETE 一起提交或回滚，否则可能只保存了一半关系。
     * 外部 Bean 调用经 Spring 事务代理，成功返回到任务时提交已完成；Redis 清理、远程查询和 MQ 不受此事务回滚保护。
     */
    @Transactional(timeout = 20, rollbackFor = Exception.class)
    public List<LikeChange> persistBatch(List<LikeChange> changes) {
        if (changes.isEmpty()) {
            return Collections.emptyList();
        }

        // 如果同一用户在一个批次内多次切换同一对象，只把最后状态写入 MySQL。
        Map<String, LikeChange> latestPerRecord = new LinkedHashMap<>();
        // 同一业务对象在批次内可能被多人点赞，只向业务方保存最新的数量快照。
        // 同一批里只发布每个对象的最新快照，减少 MQ 消息数量。
        Map<String, LikeChange> latestPerBiz = new LinkedHashMap<>();
        for (LikeChange change : changes) {
            String recordKey = recordKey(change);
            LikeChange previousRecord = latestPerRecord.get(recordKey);
            if (previousRecord == null || previousRecord.getVersion() < change.getVersion()) {
                latestPerRecord.put(recordKey, change);
            }
            String bizKey = bizKey(change.getBizType(), change.getBizId());
            LikeChange previous = latestPerBiz.get(bizKey);
            if (previous == null || previous.getVersion() < change.getVersion()) {
                latestPerBiz.put(bizKey, change);
            }
        }

        // groupingBy + mapping 得到 Map<bizType, List<bizId>>，每种类型批量校验，避免逐条跨服务查询。
        // 同一对象在不同类型中可有相同数字 ID，去重键必须带 bizType；版本比较决定新旧，不能只相信遍历顺序。
        Map<String, List<Long>> idsByType = latestPerBiz.values().stream()
                .collect(Collectors.groupingBy(LikeChange::getBizType,
                        Collectors.mapping(LikeChange::getBizId, Collectors.toList())));
        for (Map.Entry<String, List<Long>> entry : idsByType.entrySet()) {
            Set<Long> existing = targetService.existingIds(entry.getKey(), entry.getValue());
            List<Long> missing = entry.getValue().stream()
                    .filter(id -> !existing.contains(id)).collect(Collectors.toList());
            targetService.removeMissingTargets(entry.getKey(), missing);
            for (Long id : missing) {
                latestPerBiz.remove(bizKey(entry.getKey(), id));
            }
            latestPerRecord.values().removeIf(change -> entry.getKey().equals(change.getBizType())
                    && !existing.contains(change.getBizId()));
        }

        List<LikeChange> likes = new ArrayList<>();
        List<LikeChange> unlikes = new ArrayList<>();
        for (LikeChange change : latestPerRecord.values()) {
            (change.getLiked() ? likes : unlikes).add(change);
        }
        // 第 3 步：把最终 liked=true/false 分成两组，同一关系只执行最终状态的写入。
        // 批内点赞又取消可只执行 DELETE；但跨批次仍要先处理旧 pending，不能用批内去重代替全局消费顺序。
        batchPersistLikes(likes);
        batchDeleteLikes(unlikes);
        log.info("点赞变更批量落库完成，关系变更{}条，计数通知{}条",
                latestPerRecord.size(), latestPerBiz.size());
        return new ArrayList<>(latestPerBiz.values());
    }

    private void batchPersistLikes(List<LikeChange> likes) {
        if (likes.isEmpty()) {
            return;
        }
        // 单条多值 SQL 避免 MySQL 驱动逐条执行 500 次，长时间占住消费者锁。
        // uk_user_biz_type 唯一索引把同一关系限制为一行；重复键时 id=id 不改变已有行，实现插入重试幂等。
        // SQL 只写三列，其余值由数据库定义处理；不要把“无重复关系”误认为数据库已经自动校验版本。
        jdbcTemplate.update(INSERT_LIKE_SQL + placeholders(likes.size()) +
                " ON DUPLICATE KEY UPDATE id = id", parameters(likes));
    }

    private void batchDeleteLikes(List<LikeChange> unlikes) {
        if (unlikes.isEmpty()) {
            return;
        }
        // 使用 (user_id, biz_id, biz_type) 多列元组精确删除本批取消的关系。
        // 重复取消/重试删除不存在的行没有副作用；这里只删除关系，不在数据库上反复执行计数减一。
        jdbcTemplate.update(DELETE_LIKE_SQL + placeholders(unlikes.size()) + ")",
                parameters(unlikes));
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "(?, ?, ?)"));
    }

    /**
     * 将每条关系展平为 [userId, bizId, bizType, ...]，严格匹配每组 (?, ?, ?) 的列顺序。
     * 注意唯一索引定义中的字段顺序与此 SQL 参数顺序不同，绑定应以 INSERT/DELETE 的列顺序为准。
     */
    private Object[] parameters(List<LikeChange> changes) {
        List<Object> values = new ArrayList<>(changes.size() * 3);
        for (LikeChange change : changes) {
            values.add(change.getUserId());
            values.add(change.getBizId());
            values.add(change.getBizType());
        }
        return values.toArray();
    }

    private String recordKey(LikeChange change) {
        return bizKey(change.getBizType(), change.getBizId()) + ":" + change.getUserId();
    }

    private String bizKey(String bizType, Long bizId) {
        return bizType + ":" + bizId;
    }
}
