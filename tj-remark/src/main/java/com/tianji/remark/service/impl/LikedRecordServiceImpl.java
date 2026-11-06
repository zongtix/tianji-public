package com.tianji.remark.service.impl;

import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.service.ILikedRecordService;
import com.tianji.remark.service.LikeCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 点赞请求的业务入口，负责识别当前用户并处理空查询，具体读写交给 LikeCacheService。
 * 同步链路是 Controller → 本类 → Redis Lua；MySQL 落库和 MQ 通知由 LikeWriteBehindTask 异步执行。
 * 本类没有数据库事务：Redis 内部状态变更由 Lua 串行执行，数据库事务位于 LikeChangePersistenceService。
 */
@Service
@RequiredArgsConstructor
public class LikedRecordServiceImpl implements ILikedRecordService {

    private final LikeCacheService likeCacheService;

    /**
     * Controller 调用此方法设置点赞/取消状态。先确认登录身份，再校验目标、检查积压并执行缓存变更。
     * 请求表单没有 userId，避免将客户端指定的用户当作实际操作者。
     */
    @Override
    public void saveLikeRecord(LikeRecordFormDTO recordDTO) {
        // 身份链：Gateway AccountAuthFilter 校验 token 后写入 user-info 请求头，
        // 资源 SDK 的 UserInfoInterceptor 将其放入 UserContext 的 ThreadLocal<Long>，请求结束时移除。
        // Controller 与 Service 在同一请求线程中，因此可以直接取用户；FeignRelayUserInterceptor 会向远程调用透传。
        // 后台任务没有当前 HTTP 请求身份，所以落库使用 Stream 携带的 userId。
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("登录后才能点赞");
        }
        // 校验目标后，由 Lua 在状态确实变化时同时更新关系、总数和待落库日志。
        likeCacheService.change(userId, recordDTO.getBizType(), recordDTO.getBizId(), recordDTO.getLiked());
    }

    @Override
    public Set<Long> queryMyLikedBizIds(String bizType, Collection<Long> bizIds) {
        // 空 id 列表不需要访问数据库；没有登录用户时，也没有“当前用户已点赞”的记录可返回。
        Long userId = UserContext.getUser();
        if (userId == null || CollUtils.isEmpty(bizIds)) {
            return java.util.Collections.emptySet();
        }
        // 一段脚本批量检查 Set 成员，避免每个 bizId 都单独往返 Redis 一次。
        return likeCacheService.queryMyLikedBizIds(userId, bizType, bizIds);
    }

    @Override
    public Map<Long, Integer> queryLikedTimes(String bizType, Collection<Long> bizIds) {
        if (CollUtils.isEmpty(bizIds)) {
            return java.util.Collections.emptyMap();
        }
        return likeCacheService.queryLikedTimes(bizType, bizIds);
    }
}
