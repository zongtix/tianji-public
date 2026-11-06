package com.tianji.aigc;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.tianji.aigc.config.AIProperties;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.common.utils.UserContext;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.service.ChatGenerationStore;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import com.tianji.aigc.agent.AgentContext;
import com.tianji.aigc.agent.AgentDispatcher;
import com.tianji.aigc.agent.KnowledgeAgent;
import com.tianji.aigc.agent.Agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public final class ChatTestSupport {
    private ChatTestSupport() { }

    public static ChatServiceImpl ordinaryChat(ChatClient client, SystemPromptConfig prompts,
                                               ChatGenerationStore store, ChatMemory memory) {
        return withAgent(new KnowledgeAgent(client, prompts), prompts, store, memory);
    }

    public static ChatServiceImpl withAgent(Agent agent, SystemPromptConfig prompts,
                                            ChatGenerationStore store, ChatMemory memory) {
        var sessions = mock(ChatSessionService.class);
        when(sessions.requireOwnedSession(any())).thenAnswer(call -> ChatSession.builder()
                .sessionId(call.getArgument(0)).userId(UserContext.getUser()).build());
        when(sessions.requireOwnedSession(any(), any())).thenAnswer(call -> ChatSession.builder()
                .sessionId(call.getArgument(0)).userId(call.getArgument(1)).build());
        return new ChatServiceImpl(dispatcher(agent, prompts), store, memory, mock(com.tianji.aigc.service.ChatTitleGenerator.class), sessions, new KnowledgeBaseAccess(),
                new DefaultListableBeanFactory().getBeanProvider(KnowledgeRetrievalAdvisor.class),
                io.micrometer.observation.ObservationRegistry.NOOP);
    }

    /** 生命周期测试直接执行业务分支；真实路由由 AgentWorkflowTest 单独验证。 */
    public static AgentDispatcher dispatcher(ChatClient client, SystemPromptConfig prompts) {
        return dispatcher(new KnowledgeAgent(client, prompts), prompts);
    }

    private static AgentDispatcher dispatcher(Agent agent, SystemPromptConfig prompts) {
        var defaults = new SystemPromptConfig(mock(NacosConfigManager.class), new AIProperties());
        defaults.init();
        when(prompts.agentMessage(any())).thenAnswer(call -> defaults.agentMessage(call.getArgument(0)));
        when(prompts.getCourseCardContextMessage()).thenReturn(defaults.getCourseCardContextMessage());
        var dispatcher = mock(AgentDispatcher.class);
        when(dispatcher.processStream(any())).thenAnswer(call -> agent.processStream(call.getArgument(0, AgentContext.class)));
        return dispatcher;
    }
}
