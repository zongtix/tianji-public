package com.tianji.api.client.remark;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feign 查询降级工厂，按失败原因创建替代 LikedRecordClient，保持内容列表可返回。
 * 状态返回空 Set，页面按未点赞展示；总数返回空 Map，learning 只在有对应值时覆盖 PO 计数。
 * 空 Map 与 Map<ID,0> 含义不同：前者没有新快照，不能据此把数据库中的点赞数清零。
 * 降级结果是依赖故障时的展示选择，不证明用户实际上没有点赞。
 */
@Slf4j
public class LikedRecordClientFallback implements FallbackFactory<LikedRecordClient> {

    @Override
    public LikedRecordClient create(Throwable cause) {
        log.warn("查询点赞服务失败，回答列表使用数据库点赞数并按未点赞展示", cause);
        return new LikedRecordClient() {
            @Override
            public Set<Long> queryMyLikedBizIds(String bizType, List<Long> bizIds) {
                return Collections.emptySet();
            }

            @Override
            public Map<Long, Integer> queryLikedTimes(String bizType, List<Long> bizIds) {
                return Collections.emptyMap();
            }
        };
    }
}
