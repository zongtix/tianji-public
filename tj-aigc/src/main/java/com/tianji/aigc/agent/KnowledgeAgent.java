package com.tianji.aigc.agent;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.tools.PublicRuleTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
public class KnowledgeAgent extends AbstractAgent {
    private final PublicRuleTools publicRuleTools;

    /** 测试与 knowledge/public-rules 关闭场景：不注入 PublicRuleTools。 */
    public KnowledgeAgent(ChatClient client, SystemPromptConfig prompts) {
        this(client, prompts, null);
    }

    @Autowired
    public KnowledgeAgent(ChatClient client, SystemPromptConfig prompts,
                          @Nullable ObjectProvider<PublicRuleTools> publicRuleTools) {
        super(client, prompts);
        this.publicRuleTools = publicRuleTools == null ? null : publicRuleTools.getIfAvailable();
    }

    @Override
    public AgentTypeEnum getAgentType() {
        return AgentTypeEnum.KNOWLEDGE;
    }

    @Override
    protected ToolCallback[] tools(AgentContext context) {
        // 旧 knowledgeBaseId 会话不暴露 searchPublicRules；Bean 缺失时同样空集。
        if (context.knowledgeFilter() != null || publicRuleTools == null) {
            return new ToolCallback[0];
        }
        return ToolCallbacks.from(publicRuleTools);
    }

    @Override
    protected void configure(ChatClient.ChatClientRequestSpec request, AgentContext context) {
        if (StringUtils.hasText(context.clarification())) {
            request.advisors(new ClarificationAdvisor(context.clarification()));
        } else if (context.knowledgeAdvisor() != null) {
            request.advisors(context.knowledgeAdvisor()).advisors(params -> params
                    .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, context.knowledgeFilter())
                    .param(KnowledgeRetrievalAdvisor.GENERATION_ACTIVE, context.active()));
        }
    }

    /** 澄清也是最终回答：经过记忆 Advisor 正常保存，但无需再请求模型。 */
    private record ClarificationAdvisor(String text) implements StreamAdvisor {
        @Override
        public String getName() { return "agentClarification"; }

        @Override
        public int getOrder() { return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 2; }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            var response = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            return Flux.just(ChatClientResponse.builder().context(request.context()).chatResponse(response).build());
        }
    }
}
