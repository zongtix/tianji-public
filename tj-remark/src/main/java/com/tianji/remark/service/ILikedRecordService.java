package com.tianji.remark.service;

import com.tianji.remark.domain.dto.LikeRecordFormDTO;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 点赞应用层契约，供 LikedRecordController 调用，隔离 HTTP 与 Redis 实现。
 * 输入为目标状态表单或同一业务类型的对象 ID 集合；输出为已点赞 ID 集合或 ID→总数映射。
 * 当前实现 LikedRecordServiceImpl 从 UserContext 取用户身份，再委托 LikeCacheService。
 * 它没有继承 MyBatis-Plus IService：请求路径使用缓存，持久化由后台任务单独负责。
 */
public interface ILikedRecordService {
    /**
     * 设置当前用户对目标的状态；true 为点赞，false 为取消，不是把原状态反转。
     */
    void saveLikeRecord(LikeRecordFormDTO recordDTO);

    /**
     * 返回 bizIds 中当前用户已点赞的子集；未登录或列表为空时返回空集合。
     */
    Set<Long> queryMyLikedBizIds(String bizType, Collection<Long> bizIds);

    /**
     * 读取各 bizId 的当前总数，不依赖个人身份；空列表返回空 Map。
     */
    Map<Long, Integer> queryLikedTimes(String bizType, Collection<Long> bizIds);
}
