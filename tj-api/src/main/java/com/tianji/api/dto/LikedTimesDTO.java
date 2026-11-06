package com.tianji.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * remark → 业务服务的公共 MQ 计数契约，由 LikeTimesPublisher 从内部 LikeChange 组装。
 * 只包含对象定位、绝对总数及版本；QA 消费者 LikeTimesChangeListener 用它更新 interaction_reply。
 * 消费者以 liked_version < version 条件覆盖总数，重复/晚到旧消息不重复计数。
 * 它既不是客户端点赞表单，也不是 liked_record 数据库实体；不承担“哪个用户点赞”的传输。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class LikedTimesDTO {

    /** 业务类型用于路由和校验，避免不同业务的相同数字 id 混淆。 */
    private String bizType;

    /** 点赞对象的业务 id */
    private Long bizId;

    /** 点赞对象当前的总点赞数 */
    private Integer likedTimes;

    /** 单个业务对象内递增的版本号，用来丢弃乱序到达的旧快照。 */
    private Long version;
}
