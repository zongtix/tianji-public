package com.tianji.remark.service;

import com.tianji.remark.domain.enums.LikeBizType;
import com.tianji.remark.utils.LikeCacheKeys;
import com.tianji.remark.task.LikeMetricsTask;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.tianji.remark.exception.LikeServiceUnavailableException;

/**
 * 点赞请求的 Redis 读写入口，由 LikedRecordServiceImpl 调用。
 * 写入先校验目标和积压，再调用 like-change.lua；读取批量执行成员/计数脚本，减少网络往返。
 * Redis Set 保存用户关系，ZSet 保存对象总数，Hash 保存版本，Stream 保存待落库日志。
 * 这样把高频关系写入与数据库批量持久化分开；请求成功后 MySQL/业务表存在异步同步窗口。
 * 这里的 Lua 不等同于跨 Redis、MySQL、MQ 的事务，故障恢复依赖日志、重试与下游版本条件更新。
 */
@Service
@RequiredArgsConstructor
public class LikeCacheService {
    private final StringRedisTemplate redisTemplate;
    private final LikeMetricsTask metricsTask;
    private final LikeTargetService targetService;

    // 脚本对象静态复用，声明返回类型并从 classpath 加载资源；RedisTemplate 负责执行与结果反序列化。
    private static final DefaultRedisScript<Long> CHANGE_SCRIPT = changeScript();
    private static final DefaultRedisScript<List> QUERY_SCRIPT = queryScript();
    private static final DefaultRedisScript<List> COUNT_QUERY_SCRIPT = countQueryScript();

    /**
     * 设置 userId 对 bizType/bizId 的最终状态：校验 → 积压保护 → Lua 原子执行。
     * liked=true 时通过 LearningClient 确认 QA 目标存在；取消不做此校验，允许清理已删除目标的关系。
     * 脚本返回 1=有效变化、0=原本已是该状态、-1=缓存未就绪；前两种都视为成功。
     * 同方向重复请求只在 Set 首次改变时修改计数和日志，不会因为 HTTP 重试多加/多减。
     */
    public void change(Long userId, String bizType, Long bizId, Boolean liked) {
        validateBizType(bizType);
        if (Boolean.TRUE.equals(liked)) {
            targetService.requireExists(bizType, bizId);
        }
        if (metricsTask.rejectingWrites()) {
            throw new LikeServiceUnavailableException("点赞积压或监控状态过期，暂时拒绝新请求");
        }

        // 第 1 步：用类型隔离不同业务，按用户保存已点赞对象集合。
        // 例如 remark:like:user:QA:42 中的成员 1001，表示用户 42 点赞了回答/评论 1001。
        String userSet = LikeCacheKeys.userBizSet(bizType, userId);
        // READY 与关系变更必须由同一 Lua 脚本检查，避免重建时有写入穿过停写窗口。
        List<String> keys = Arrays.asList(
                userSet,
                LikeCacheKeys.countSortedSet(bizType),
                LikeCacheKeys.versionHash(bizType),
                LikeCacheKeys.CHANGE_STREAM,
                LikeCacheKeys.READY_KEY);

        // Lua 只对真正改变了 Set 的操作增加版本并写 Stream，重复点赞不会重复计数或落库。
        // 第 2 步：KEYS 顺序必须与 Lua 一致；ARGV 顺序是 bizId、userId、期望状态、bizType。
        // DefaultRedisScript<Long> 指定整数返回类型；Redis 注入 KEYS/ARGV，脚本不依赖 Java 线程内的用户上下文。
        Long changed;
        try {
            changed = redisTemplate.execute(CHANGE_SCRIPT, keys,
                    bizId.toString(), userId.toString(), liked ? "1" : "0", bizType);
        } catch (DataAccessException e) {
            throw new LikeServiceUnavailableException("点赞服务暂不可用，请稍后重试", e);
        }
        if (changed == null) {
            throw new LikeServiceUnavailableException("点赞服务暂不可用，请稍后重试");
        }
        if (changed == -1L) {
            throw new LikeServiceUnavailableException("点赞缓存正在初始化，请稍后重试");
        }
    }

    /**
     * 读取 userId 在 bizType 下的关系：把非空 bizIds 转为脚本参数，一次 SISMEMBER 批量检查。
     * 脚本返回已点赞 ID 列表，Java 转成 Set<Long>，供列表 VO 使用 contains 判断个人状态。
     * 它与总数查询分开，因为“我点赞了”与“所有人总共点了多少赞”来自不同数据结构。
     */
    public Set<Long> queryMyLikedBizIds(Long userId, String bizType, Collection<Long> bizIds) {
        validateBizType(bizType);
        ensureReady();

        String key = LikeCacheKeys.userBizSet(bizType, userId);
        List<String> args = new ArrayList<>(bizIds.size());
        for (Long bizId : bizIds) {
            if (bizId != null) {
                args.add(bizId.toString());
            }
        }
        if (args.isEmpty()) {
            return Collections.emptySet();
        }

        // 一次脚本调用完成这一页的成员检查；返回值只包含已点赞的业务 id。
        List<?> result;
        try {
            result = redisTemplate.execute(QUERY_SCRIPT, Collections.singletonList(key), args.toArray());
        } catch (DataAccessException e) {
            throw new LikeServiceUnavailableException("点赞服务暂不可用，请稍后重试", e);
        }
        Set<Long> likedIds = new HashSet<>();
        if (result != null) {
            for (Object value : result) {
                likedIds.add(Long.valueOf(value.toString()));
            }
        }
        return likedIds;
    }

    /**
     * 按业务类型从 ZSet 批量读取即时总数；脚本将缺失成员按 0 返回。
     * 返回扁平列表 [id1, count1, id2, count2, ...]，每两个元素还原为 Map<Long, Integer>。
     * learning 用此 Map 覆盖数据库中尚未同步的计数，减少异步落库期间的展示延迟。
     */
    public Map<Long, Integer> queryLikedTimes(String bizType, Collection<Long> bizIds) {
        validateBizType(bizType);
        ensureReady();

        List<String> args = new ArrayList<>(bizIds.size());
        for (Long bizId : bizIds) {
            if (bizId != null) {
                args.add(bizId.toString());
            }
        }
        if (args.isEmpty()) {
            return Collections.emptyMap();
        }

        List<?> result;
        try {
            result = redisTemplate.execute(COUNT_QUERY_SCRIPT,
                    Collections.singletonList(LikeCacheKeys.countSortedSet(bizType)), args.toArray());
        } catch (DataAccessException e) {
            throw new LikeServiceUnavailableException("点赞服务暂不可用，请稍后重试", e);
        }
        Map<Long, Integer> likedTimes = new java.util.HashMap<>(args.size());
        if (result != null) {
            // Lua 返回 id/count 交替排列的列表，映射回业务对象 id 到点赞数。
            for (int i = 0; i + 1 < result.size(); i += 2) {
                likedTimes.put(Long.valueOf(result.get(i).toString()),
                        Integer.valueOf(result.get(i + 1).toString()));
            }
        }
        return likedTimes;
    }

    private void validateBizType(String bizType) {
        try {
            LikeBizType.valueOf(bizType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的点赞业务类型：" + bizType, e);
        }
    }

    /**
     * 读路径先确认缓存可用，避免重建时把暂时缺失的关系/计数当作真实零值。
     * 写路径的 READY 检查放在 Lua 内，与变更不被其他请求穿插执行；这里是一次单独的读取。
     */
    private void ensureReady() {
        final String state;
        try {
            state = redisTemplate.opsForValue().get(LikeCacheKeys.READY_KEY);
        } catch (DataAccessException e) {
            throw new LikeServiceUnavailableException("点赞服务暂不可用，请稍后重试", e);
        }
        if (!"READY".equals(state)) {
            throw new LikeServiceUnavailableException("点赞缓存正在初始化，请稍后重试");
        }
    }

    private static DefaultRedisScript<Long> changeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/like-change.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<List> queryScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/query-liked-biz-ids.lua"));
        script.setResultType(List.class);
        return script;
    }

    private static DefaultRedisScript<List> countQueryScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/query-liked-times.lua"));
        script.setResultType(List.class);
        return script;
    }
}
