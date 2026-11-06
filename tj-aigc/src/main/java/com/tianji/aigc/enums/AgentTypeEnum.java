package com.tianji.aigc.enums;

/** 只注册当前已经实现的智能体，业务分类不能再次分发给路由自身。 */
public enum AgentTypeEnum {
    ROUTE,
    RECOMMEND,
    CONSULT,
    KNOWLEDGE
}
