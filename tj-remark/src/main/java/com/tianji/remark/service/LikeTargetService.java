package com.tianji.remark.service;

import com.tianji.api.client.learning.LearningClient;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.remark.exception.LikeServiceUnavailableException;
import com.tianji.remark.utils.LikeCacheKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 点赞对象存在性与删除清理的协作服务，供请求、落库、恢复补发及重建共同调用。
 * QA 对象归 learning 管理，通过 LearningClient 批量获取存在 ID；本地 liked_record 只记录关系，不能证明对象存在。
 * 结果 Set 用于过滤已删除对象，查询失败则中断处理，避免把远程故障误当成“所有对象已删除”。
 * NOTE 尚无目标服务，当前原样返回输入 ID；这段行为只说明入口开放，不证明笔记真实存在。
 */
@Service
@RequiredArgsConstructor
public class LikeTargetService {
    private static final int EXISTENCE_BATCH_SIZE = 100;

    private final LearningClient learningClient;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public void requireExists(String bizType, Long bizId) {
        if (!existingIds(bizType, Collections.singletonList(bizId)).contains(bizId)) {
            throw new BadRequestException("点赞对象不存在");
        }
    }

    /**
     * 按类型返回 bizIds 中仍存在的对象集合；QA 去重并分组远程查询，再合并成 Set。
     * 请求与后台任务均使用此结果：前者拒绝无效点赞，后者过滤消息并清理关系。
     */
    public Set<Long> existingIds(String bizType, Collection<Long> bizIds) {
        if (bizIds.isEmpty()) {
            return Collections.emptySet();
        }
        if ("NOTE".equals(bizType)) {
            // 沿用 NOTE 入口保持开放的既有决定；目标服务未接入，不能将未知当成删除。
            return new HashSet<>(bizIds);
        }
        if (!"QA".equals(bizType)) {
            return Collections.emptySet();
        }
        List<Long> uniqueIds = new ArrayList<>(new HashSet<>(bizIds));
        Set<Long> result = new HashSet<>();
        try {
            // 100 个 Long ID 的 GET 查询约 2.4KB，避免 500 个长 ID 超过请求行限制。
            for (int start = 0; start < uniqueIds.size(); start += EXISTENCE_BATCH_SIZE) {
                List<Long> batch = uniqueIds.subList(start,
                        Math.min(start + EXISTENCE_BATCH_SIZE, uniqueIds.size()));
                Set<Long> existing = learningClient.queryExistingReplyIds(batch);
                if (existing == null) {
                    throw new IllegalStateException("目标存在性查询返回空响应");
                }
                result.addAll(existing);
            }
        } catch (RuntimeException e) {
            // 任一分批失败都不能返回部分结果，否则会误删后续分批中的有效目标。
            throw new LikeServiceUnavailableException("暂时无法确认点赞对象，请稍后重试", e);
        }
        return result;
    }

    /**
     * 只接收已经确认删除的对象 ID，清理所有用户 Set、数量 ZSet、版本 Hash 及 MySQL 关系。
     * Redis 清理和 SQL 删除不是一个跨存储事务；若由 persistBatch 调用，SQL 参与其数据库事务，Redis 不参与。
     */
    public void removeMissingTargets(String bizType, Collection<Long> bizIds) {
        if (bizIds.isEmpty()) {
            return;
        }
        // Stream.map 将每个 Long ID 编码为 UTF-8 byte[]，toArray 得到 byte[][]，供底层 Redis 命令一次删除多个成员。
        // SCAN 游标渐进遍历用户 Key；count 是扫描提示值，不是一次固定返回数，也不是 SQL 分页。
        byte[][] members = bizIds.stream().map(id -> bytes(id.toString())).toArray(byte[][]::new);
        // 已删除目标也可能留在尚未落库用户的 Set，不能只清理数据库中能找到的用户。
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(LikeCacheKeys.PREFIX + "user:" + bizType + ":*").count(500).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    connection.sRem(cursor.next(), members);
                }
            }
            connection.zRem(bytes(LikeCacheKeys.countSortedSet(bizType)), members);
            connection.hDel(bytes(LikeCacheKeys.versionHash(bizType)), members);
            return null;
        });
        List<Object> parameters = new ArrayList<>(bizIds.size() + 1);
        parameters.add(bizType);
        parameters.addAll(bizIds);
        // 用同一 biz_type 和 IN 列表删除对应关系，避免 QA/NOTE 数字 ID 相同而互相误删。
        // 问号占位符绑定参数；SQL 列表长度来自已知集合大小，实际业务值不直接拼接到 SQL。
        jdbcTemplate.update("DELETE FROM liked_record WHERE biz_type = ? AND biz_id IN (" +
                String.join(",", Collections.nCopies(bizIds.size(), "?")) + ")", parameters.toArray());
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
