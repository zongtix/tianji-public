package com.tianji.aigc.knowledge.material;

import com.tianji.aigc.agent.AbstractAgent;
import com.tianji.aigc.agent.AgentContext;
import com.tianji.aigc.agent.ConsultAgent;
import com.tianji.aigc.config.AIProperties;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.RagRequestGuard;
import com.tianji.aigc.mcp.McpCallScope;
import com.tianji.aigc.tools.CourseMaterialTools;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.alibaba.cloud.nacos.NacosConfigManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 闸 A 确定性骨架：对齐 docs/rag-evaluation/course-materials/acceptance-checklist.md。 */
class CourseMaterialGateATest {
    private final CourseMaterialSearch search = mock(CourseMaterialSearch.class);
    private final CourseMaterialAccess access =
            CourseMaterialAccess.fromClasspath("course-materials/manifest.json");
    private final CourseMaterialTools tools = new CourseMaterialTools(search, access, 2, 3);

    @Test
    void consultWithoutMaterialBeanOmitsLectureTool() throws Exception {
        // knowledgeFilter 非空时 AgentDispatcher 走 KNOWLEDGE；无讲义 Bean 的 CONSULT 不含讲义工具。
        var consult = consultAgent(null);
        assertThat(toolNames(consult)).containsExactlyInAnyOrder("searchCourses", "queryCourseById");
    }

    @Test
    void consultWithMaterialBeanExposesLectureTool() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<CourseMaterialTools> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tools);
        var consult = consultAgent(provider);
        assertThat(toolNames(consult)).contains("searchCourseMaterials", "searchCourses", "queryCourseById");
    }

    @Test
    void authorizeDenialDoesNotCallPipeline() {
        var result = tools.searchCourseMaterials(999L, "缓存击穿", toolContext(1L, () -> true));
        assertThat(result.status()).isEqualTo("ACCESS_DENIED");
        verify(search, never()).search(anyString(), any(), any());
    }

    @Test
    void redisFilterExpressionNeverIncludesMysqlMaterialIds() {
        var decision = access.authorize(1L, "910000000000010005");
        assertThat(decision.status()).isEqualTo(CourseMaterialAccess.Status.OK);
        assertThat(decision.filterExpression())
                .contains("910000000000010005")
                .contains("redis-hotspot-faq-01")
                .doesNotContain("mysql-index-faq-01")
                .doesNotContain("910000000000010004");
    }

    @Test
    void cancelledScopeStopsMaterialTool() {
        AtomicBoolean active = new AtomicBoolean(false);
        try {
            tools.searchCourseMaterials(910000000000010005L, "缓存击穿", toolContext(1L, active::get));
        } catch (RuntimeException error) {
            assertThat(McpCallScope.isCancellation(error)).isTrue();
        }
        verify(search, never()).search(anyString(), any(), any());
    }

    @Test
    void promptFilesMentionCourseMaterialTool() throws Exception {
        assertThat(read("prompts/route-system-message.txt")).contains("讲义");
        assertThat(read("prompts/consult-system-message.txt")).contains("searchCourseMaterials");
        assertThat(read("prompts/course-tool-preparation.txt")).contains("searchCourseMaterials");
    }

    @Test
    void okPathUsesServerFilterAndCapsEvidence() {
        when(search.search(anyString(), any(Filter.Expression.class), any(RagRequestGuard.class)))
                .thenReturn(new KnowledgeRetrievalPipeline.Result(List.of(), List.of(), "not_executed"));
        var result = tools.searchCourseMaterials(910000000000010005L, "缓存击穿", toolContext(1L, () -> true));
        assertThat(result.status()).isEqualTo("EMPTY");
        verify(search).search(anyString(), any(Filter.Expression.class), any(RagRequestGuard.class));
    }

    private ConsultAgent consultAgent(ObjectProvider<CourseMaterialTools> materials) {
        var prompts = new SystemPromptConfig(mock(NacosConfigManager.class), new AIProperties());
        prompts.init();
        var courseTools = new CourseTools(mock(CourseClient.class), mock(SearchClient.class));
        var client = ChatClient.create(mock(ChatModel.class));
        return materials == null
                ? new ConsultAgent(client, prompts, courseTools)
                : new ConsultAgent(client, prompts, courseTools, materials);
    }

    private List<String> toolNames(ConsultAgent consult) throws Exception {
        Method method = AbstractAgent.class.getDeclaredMethod("tools", AgentContext.class);
        method.setAccessible(true);
        ToolCallback[] callbacks = (ToolCallback[]) method.invoke(consult, (AgentContext) null);
        return Arrays.stream(callbacks).map(tool -> tool.getToolDefinition().name()).toList();
    }

    private ToolContext toolContext(Long userId, java.util.function.BooleanSupplier active) {
        Map<String, Object> values = new HashMap<>();
        values.put(McpCallScope.CONTEXT_KEY, new McpCallScope(active, () -> true));
        values.put(CourseMaterialTools.TURN_CALLS_KEY, new AtomicInteger(0));
        if (userId != null) values.put(CourseMaterialTools.USER_ID_KEY, userId);
        return new ToolContext(values);
    }

    private String read(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
