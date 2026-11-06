package com.tianji.remark.domain.enums;

/**
 * 点赞业务类型边界，由 LikeCacheService/Stream 解析校验，并决定 Redis 命名和 MQ 路由。
 * QA 已接入 learning 的回答/评论存在性校验和计数消费者；NOTE 入口开放，有兜底队列，尚未接入目标服务。
 * 增加类型需要同时考虑表单校验、目标校验、缓存 Key、消息绑定和下游计数消费者。
 */
public enum LikeBizType {
    // QA 的 bizId 是 interaction_reply.id，回答和回答下的评论都属于这个类型。
    QA,
    // NOTE 当前按目标存在处理；枚举值存在不等于笔记业务链路已经实现。
    NOTE
}
