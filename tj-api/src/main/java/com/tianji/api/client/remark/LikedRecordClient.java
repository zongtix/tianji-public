package com.tianji.api.client.remark;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 供 learning 调用 remark-service 的 OpenFeign 契约，方法映射到 /likes/status 与 /likes/times。
 * 列表一次提交一页 ID，返回 Set<已点赞ID> 和 Map<ID,总数>，避免为每条回答各发远程请求。
 * FeignRelayUserInterceptor 透传当前用户身份头，因此 status 查询的是原页面用户的关系。
 * 远程服务失败由 LikedRecordClientFallback 降级；调用方仍可返回内容和数据库中上次同步的计数。
 */
@FeignClient(value = "remark-service", path = "likes",
        fallbackFactory = LikedRecordClientFallback.class)
public interface LikedRecordClient {

    /**
     * 批量查询当前用户已点赞的业务 id。
     *
     * @param bizType 业务类型，例如 QA
     * @param bizIds  本次需要查询的业务 id 集合
     * @return 当前用户已点赞的业务 id 集合
     */
    @GetMapping("/status")
    Set<Long> queryMyLikedBizIds(
            @RequestParam("bizType") String bizType,
            @RequestParam("bizIds") List<Long> bizIds);

    /** 批量查询指定业务对象的点赞总数，返回值不包含未点赞状态而是业务 id 到总数的映射。 */
    @GetMapping("/times")
    Map<Long, Integer> queryLikedTimes(
            @RequestParam("bizType") String bizType,
            @RequestParam("bizIds") List<Long> bizIds);
}
