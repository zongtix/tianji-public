package com.tianji.aigc.agent;

import com.tianji.aigc.enums.AgentTypeEnum;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/** 智能体负责模型调用；租约、停止、SSE 和回答补存统一由聊天服务管理。 */
public interface Agent {
    AgentTypeEnum getAgentType();

    Flux<ChatResponse> processStream(AgentContext context);
}
