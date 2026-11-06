package com.tianji.aigc.agent;

import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.longterm.UserMemoryContext;
import com.tianji.aigc.tools.CourseRecommendations;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 每轮请求独立创建；授权范围、对话标识和生成状态均由服务端提供。
 * active 决定能否进入下一阶段；ownsGeneration 只检查请求归属，用于卡片保存。
 * 原始问题始终保留，路由和检索使用的内部文本不会替换它。
 * userId 为服务端已核验身份，可为 null（工具侧拒绝讲义授权），不得从 conversationId 解析。
 */
public record AgentContext(
        String question,
        String conversationId,
        Long userId,
        CourseRecommendations recommendations,
        BooleanSupplier active,
        BooleanSupplier ownsGeneration,
        KnowledgeRetrievalAdvisor knowledgeAdvisor,
        String knowledgeFilter,
        List<Message> history,
        String clarification,
        UserMemoryContext userMemory) {

    /** 测试与旧调用兼容：未传入时 userId 为空。 */
    public AgentContext(String question, String conversationId, CourseRecommendations recommendations,
                        BooleanSupplier active, BooleanSupplier ownsGeneration, KnowledgeRetrievalAdvisor knowledgeAdvisor,
                        String knowledgeFilter, List<Message> history, String clarification) {
        this(question, conversationId, null, recommendations, active, ownsGeneration, knowledgeAdvisor,
                knowledgeFilter, history, clarification, null);
    }

    public AgentContext(String question, String conversationId, CourseRecommendations recommendations,
                        BooleanSupplier active, BooleanSupplier ownsGeneration, KnowledgeRetrievalAdvisor knowledgeAdvisor,
                        String knowledgeFilter, List<Message> history, String clarification,
                        UserMemoryContext userMemory) {
        this(question, conversationId, null, recommendations, active, ownsGeneration, knowledgeAdvisor,
                knowledgeFilter, history, clarification, userMemory);
    }

    public AgentContext withHistory(List<Message> messages) {
        return new AgentContext(question, conversationId, userId, recommendations, active, ownsGeneration,
                knowledgeAdvisor, knowledgeFilter, List.copyOf(messages), clarification, userMemory);
    }

    public AgentContext withClarification(String text) {
        return new AgentContext(question, conversationId, userId, recommendations, active, ownsGeneration,
                knowledgeAdvisor, knowledgeFilter, history, text, userMemory);
    }
}
