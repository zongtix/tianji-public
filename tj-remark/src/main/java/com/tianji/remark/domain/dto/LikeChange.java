package com.tianji.remark.domain.dto;

import lombok.Data;

/**
 * 内部变更 DTO：LikeWriteBehindTask 将 Redis Stream 字段解析成此对象，再交给批量落库服务。
 * userId + bizType + bizId + liked 描述一条关系的目标状态；likedTimes + version 描述该对象的数量快照。
 * 关系合并按“用户+类型+对象”，通知合并按“类型+对象”，两者不能混为同一个去重维度。
 * 它也被恢复逻辑当作计数快照载体，此时可不含 userId/liked；对外 MQ 使用更小的 LikedTimesDTO。
 */
@Data
public class LikeChange {
    private Long userId;
    private String bizType;
    private Long bizId;
    private Boolean liked;
    private Integer likedTimes;
    // version 属于业务对象，多个用户对同一对象的有效变更共享同一条递增序列。
    private Long version;
}
