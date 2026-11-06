package com.tianji.aigc.agent;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/** 会话记忆读入后、课程工具准备前，约束普通业务 Agent 的请求预算。 */
public final class ChatContextBudgetAdvisor implements BaseAdvisor {
    private final ChatContextBudget budget;

    public ChatContextBudgetAdvisor(ChatContextBudget budget) { this.budget = budget; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request.mutate().prompt(budget.fit(request.prompt())).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { return response; }

    @Override
    public int getOrder() { return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1; }
}
