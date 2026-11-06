package com.tianji.aigc.agent;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.tianji.aigc.config.AIProperties;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.tools.CourseRecommendations;
import com.tianji.aigc.tools.PublicRuleTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 5：KNOWLEDGE 仅在普通会话挂载 searchPublicRules，并校验 toolContext 键。 */
class KnowledgeAgentToolsMountTest {
    private SystemPromptConfig prompts;
    private ChatClient client;
    private PublicRuleTools publicRuleTools;

    @BeforeEach
    void setUp() {
        prompts = new SystemPromptConfig(mock(NacosConfigManager.class), new AIProperties());
        prompts.init();
        client = ChatClient.create(mock(ChatModel.class));
        publicRuleTools = new PublicRuleTools(
                mock(KnowledgeRetrievalPipeline.class), new KnowledgeBaseAccess(), 3, 3);
    }

    @Test
    void ordinarySessionWithBeanExposesSearchPublicRules() {
        var agent = knowledgeAgent(provider(publicRuleTools));
        assertThat(toolNames(agent, ordinaryContext(1L))).containsExactly("searchPublicRules");
    }

    @Test
    void legacyKnowledgeFilterSessionReturnsEmptyToolsEvenWithBean() {
        var agent = knowledgeAgent(provider(publicRuleTools));
        assertThat(toolNames(agent, legacyContext(1L))).isEmpty();
    }

    @Test
    void absentPublicRuleToolsBeanReturnsEmptyTools() {
        var agent = new KnowledgeAgent(client, prompts);
        assertThat(toolNames(agent, ordinaryContext(1L))).isEmpty();
    }

    @Test
    void nullProviderReturnsEmptyTools() {
        var agent = knowledgeAgent(null);
        assertThat(toolNames(agent, ordinaryContext(1L))).isEmpty();
    }

    @Test
    void toolContextCarriesPublicRuleKeysForOrdinarySession() {
        var agent = knowledgeAgent(provider(publicRuleTools));
        Map<String, Object> values = agent.toolContextForTest(ordinaryContext(42L));

        assertThat(values.get(PublicRuleTools.USER_ID_KEY)).isEqualTo(42L);
        assertThat(values.get(PublicRuleTools.TURN_CALLS_KEY)).isInstanceOf(AtomicInteger.class);
        assertThat(values.get(PublicRuleTools.DOMAIN_CALLS_KEY)).isInstanceOf(Set.class);
        assertThat(values.get(PublicRuleTools.LEGACY_SESSION_KEY)).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void toolContextMarksLegacySessionWhenKnowledgeFilterSet() {
        var agent = knowledgeAgent(provider(publicRuleTools));
        Map<String, Object> values = agent.toolContextForTest(legacyContext(42L));

        assertThat(values.get(PublicRuleTools.LEGACY_SESSION_KEY)).isEqualTo(Boolean.TRUE);
        assertThat(values.get(PublicRuleTools.USER_ID_KEY)).isEqualTo(42L);
        assertThat(values.get(PublicRuleTools.TURN_CALLS_KEY)).isInstanceOf(AtomicInteger.class);
        assertThat(values.get(PublicRuleTools.DOMAIN_CALLS_KEY)).isInstanceOf(Set.class);
    }

    @Test
    void toolContextOmitsUserIdWhenAbsent() {
        var agent = knowledgeAgent(provider(publicRuleTools));
        Map<String, Object> values = agent.toolContextForTest(ordinaryContext(null));

        assertThat(values).doesNotContainKey(PublicRuleTools.USER_ID_KEY);
        assertThat(values).doesNotContainKey(com.tianji.aigc.tools.CourseMaterialTools.USER_ID_KEY);
    }

    private KnowledgeAgent knowledgeAgent(ObjectProvider<PublicRuleTools> provider) {
        return new KnowledgeAgent(client, prompts, provider);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<PublicRuleTools> provider(PublicRuleTools tools) {
        ObjectProvider<PublicRuleTools> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tools);
        return provider;
    }

    private List<String> toolNames(KnowledgeAgent agent, AgentContext context) {
        ToolCallback[] callbacks = agent.tools(context);
        return Arrays.stream(callbacks).map(tool -> tool.getToolDefinition().name()).toList();
    }

    private AgentContext ordinaryContext(Long userId) {
        return new AgentContext("积分怎么算", "c1", userId, new CourseRecommendations(),
                () -> true, () -> true, null, null, List.of(), null, null);
    }

    private AgentContext legacyContext(Long userId) {
        return new AgentContext("积分怎么算", "c1", userId, new CourseRecommendations(),
                () -> true, () -> true, null, "documentId == 'tianji-points-guide'", List.of(), null, null);
    }
}
