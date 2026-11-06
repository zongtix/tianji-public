package com.tianji.remark.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.utils.LikeCacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 抽样对账 Redis 用户关系/对象总数与 MySQL liked_record，只报告差异，不自动修改或修复数据。
 * 仅在 READY 且 Stream 无正文时开始，结束时再次检查长度和 lastGeneratedId，减少异步中间态的误报。
 * 它是观察工具，不是全量一致性证明；采样主要来自最近新增关系，未覆盖的用户/对象不能据此确认无差异。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeReconcileTask {
    private static final String USER_KEY_PREFIX = LikeCacheKeys.PREFIX + "user:";
    private final StringRedisTemplate redisTemplate;
    private final LikedRecordMapper mapper;

    @Value("${tj.like.reconcile.sample-users:50}")
    private int sampleUsers = 50;

    /**
     * 默认每五分钟抽样；查询前后状态稳定才输出差异，避免将正常的写后同步窗口当成错误。
     */
    @Scheduled(fixedDelayString = "${tj.like.reconcile.interval-ms:300000}")
    public void reconcile() {
        try {
            if (!"READY".equals(redisTemplate.opsForValue().get(LikeCacheKeys.READY_KEY))) {
                return;
            }
            StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
            if (!Long.valueOf(0).equals(streams.size(LikeCacheKeys.CHANGE_STREAM))) {
                return;
            }
            StreamInfo.XInfoStream info = streams.info(LikeCacheKeys.CHANGE_STREAM);
            String beforeId = info == null ? null : info.lastGeneratedId();
            if (beforeId == null) {
                log.error("ALERT[reconcile] 点赞 Stream 不存在，无法对账");
                return;
            }
            List<String> differences = compareSample();
            // 比对期间发生新写入，即使它已被快速落库，也不能把中间态报成差异。
            if (!"READY".equals(redisTemplate.opsForValue().get(LikeCacheKeys.READY_KEY)) ||
                    !Long.valueOf(0).equals(streams.size(LikeCacheKeys.CHANGE_STREAM)) ||
                    !beforeId.equals(streams.info(LikeCacheKeys.CHANGE_STREAM).lastGeneratedId())) {
                return;
            }
            for (String difference : differences) {
                log.error("ALERT[reconcile] {}", difference);
            }
        } catch (DataAccessException e) {
            log.error("ALERT[reconcile] 点赞对账读取 Redis 或 MySQL 失败", e);
        }
    }

    /**
     * 按关系主键倒序抽取用户，构建去重且保留顺序的 LinkedHashSet<用户Key>。
     * 每个样本读取 Redis Set 与数据库 ID Set，检查并集以同时发现“Redis多了”与“MySQL多了”。
     * comparedCounts 是 Set<类型:ID>，让多个用户点赞同一对象时只对该计数抽查一次，并限制总量。
     */
    private List<String> compareSample() {
        int limit = Math.max(1, sampleUsers);
        Set<String> userKeys = new LinkedHashSet<>(limit);
        // 主键倒序抽最近新增的关系，避免在大表上按未确认有索引的更新时间排序。
        List<LikedRecord> recent = mapper.selectList(new QueryWrapper<LikedRecord>()
                .select("user_id", "biz_type")
                .orderByDesc("id")
                .last("LIMIT " + limit * 4));
        for (LikedRecord record : recent) {
            userKeys.add(LikeCacheKeys.userBizSet(record.getBizType(), record.getUserId()));
            if (userKeys.size() == limit) {
                break;
            }
        }

        List<String> differences = new ArrayList<>();
        Set<String> comparedCounts = new HashSet<>();
        for (String key : userKeys) {
            String[] parts = key.substring(USER_KEY_PREFIX.length()).split(":", 2);
            if (parts.length != 2) {
                differences.add("用户关系 key 格式错误 key=" + key);
                continue;
            }
            String bizType = parts[0];
            Long userId;
            try {
                userId = Long.valueOf(parts[1]);
            } catch (NumberFormatException e) {
                differences.add("用户关系 key 的 userId 非数字 key=" + key);
                continue;
            }
            Set<String> redisIds = redisTemplate.opsForSet().members(key);
            if (redisIds == null) {
                throw new IllegalStateException("Redis SMEMBERS 返回 null，key=" + key);
            }
            // 这里只需要 biz_id：同一 user_id + biz_type 的关系行转成 Set 后，contains 即可判断是否一致。
            // 不加载完整实体信息，也不查用户昵称，避免对账依赖无关服务。
            List<LikedRecord> databaseRows = mapper.selectList(new QueryWrapper<LikedRecord>()
                    .select("biz_id")
                    .eq("user_id", userId)
                    .eq("biz_type", bizType));
            Set<String> databaseIds = new HashSet<>();
            for (LikedRecord row : databaseRows) {
                databaseIds.add(row.getBizId().toString());
            }
            Set<String> allIds = new LinkedHashSet<>(redisIds);
            allIds.addAll(databaseIds);
            for (String bizId : allIds) {
                boolean redisLiked = redisIds.contains(bizId);
                boolean mysqlLiked = databaseIds.contains(bizId);
                if (redisLiked != mysqlLiked) {
                    differences.add("点赞关系差异 bizType=" + bizType + " userId=" + userId +
                            " bizId=" + bizId + " redis=" + redisLiked + " mysql=" + mysqlLiked);
                }
                String countKey = bizType + ":" + bizId;
                if (comparedCounts.size() < limit && comparedCounts.add(countKey)) {
                    compareCount(bizType, bizId, differences);
                }
            }
        }
        return differences;
    }

    /**
     * 以关系表中同类型/同对象的行数为数据库总数，与 ZSet score 比较；本表本身没有 liked_times 字段。
     */
    private void compareCount(String bizType, String bizId, List<String> differences) {
        final Long numericId;
        try {
            numericId = Long.valueOf(bizId);
        } catch (NumberFormatException e) {
            differences.add("业务 id 非数字 bizType=" + bizType + " bizId=" + bizId);
            return;
        }
        Double score = redisTemplate.opsForZSet().score(LikeCacheKeys.countSortedSet(bizType), bizId);
        Long rows = mapper.selectCount(new QueryWrapper<LikedRecord>()
                .eq("biz_type", bizType)
                .eq("biz_id", numericId));
        long mysqlCount = rows == null ? 0 : rows;
        double redisCount = score == null ? 0 : score;
        if (redisCount != mysqlCount) {
            differences.add("点赞计数差异 bizType=" + bizType + " bizId=" + bizId +
                    " redis=" + redisCount + " mysql=" + mysqlCount);
        }
    }
}
