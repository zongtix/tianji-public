package com.tianji.aigc.knowledge.material;

import com.tianji.aigc.config.KnowledgeRagProperties;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.KnowledgeContextSelector;
import com.tianji.aigc.knowledge.KnowledgeKeywordSearch;
import com.tianji.aigc.knowledge.KnowledgeReranker;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.RagTelemetry;
import com.tianji.aigc.tools.CourseMaterialTools;
import com.tianji.aigc.mcp.McpCallScope;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸 A 真 ES 冒烟：需环境变量 TJ_AIGC_GATE_A_LIVE=1，以及导入脚本已写入讲义索引。
 * 不启动完整聊天服务，只验证授权 filter + 检索不串课。
 */
@EnabledIfEnvironmentVariable(named = "TJ_AIGC_GATE_A_LIVE", matches = "1")
class CourseMaterialLiveSmokeIT {

    @Test
    void redisQuestionDoesNotReturnMysqlMaterials() throws Exception {
        Assumptions.assumeTrue(System.getenv("TJ_AIGC_ES_PASSWORD") != null
                && System.getenv("DASHSCOPE_API_KEY") != null
                && System.getenv("DASHSCOPE_BASE_URL") != null);

        var properties = new LinkedHashMap<String, Object>();
        properties.put("tj.ai.knowledge.enabled", true);
        properties.put("tj.ai.course-materials.enabled", true);
        properties.put("tj.ai.knowledge.store-type", "elasticsearch");
        properties.put("spring.elasticsearch.uris",
                System.getenv().getOrDefault("SPRING_ELASTICSEARCH_URIS", "http://127.0.0.1:19201"));
        properties.put("spring.elasticsearch.username",
                System.getenv().getOrDefault("SPRING_ELASTICSEARCH_USERNAME", "tj_aigc_app"));
        properties.put("spring.elasticsearch.password", System.getenv("TJ_AIGC_ES_PASSWORD"));
        properties.put("tj.ai.knowledge.embedding.api-key", System.getenv("DASHSCOPE_API_KEY"));
        properties.put("tj.ai.knowledge.embedding.base-url", System.getenv("DASHSCOPE_BASE_URL"));
        properties.put("tj.ai.knowledge.embedding.model", "text-embedding-v4");
        properties.put("tj.ai.knowledge.embedding.dimensions", 1024);
        properties.put("tj.ai.knowledge.index-name", CourseMaterialSearch.INDEX);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("gate-a-live", properties));
            context.register(JacksonAutoConfiguration.class, KnowledgeStoreConfig.class,
                    ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class);
            context.refresh();

            var vectors = context.getBean(VectorStore.class);
            var settings = new KnowledgeRagProperties();
            var search = SearchRequest.builder().topK(3).similarityThreshold(0.3).build();
            var telemetry = new RagTelemetry(ObservationRegistry.NOOP);
            var selector = new KnowledgeContextSelector(settings.getContext());
            var clients = context.getBeanProvider(co.elastic.clients.elasticsearch.ElasticsearchClient.class);
            var keywords = new KnowledgeKeywordSearch(() -> {
                assertThat(vectors).isInstanceOf(ElasticsearchVectorStore.class);
                return clients.getObject();
            }, CourseMaterialSearch.INDEX);
            var pipeline = new KnowledgeRetrievalPipeline(vectors, keywords,
                    new KnowledgeReranker(settings.getRerank(), selector), search, settings, telemetry);
            var materialSearch = new CourseMaterialSearch(pipeline);
            var access = CourseMaterialAccess.fromClasspath("course-materials/manifest.json");
            var tools = new CourseMaterialTools(materialSearch, access, 2, 3);

            Map<String, Object> toolCtx = new HashMap<>();
            toolCtx.put(McpCallScope.CONTEXT_KEY, new McpCallScope(() -> true, () -> true));
            toolCtx.put(CourseMaterialTools.TURN_CALLS_KEY, new AtomicInteger(0));
            toolCtx.put(CourseMaterialTools.USER_ID_KEY, 1L);
            var result = tools.searchCourseMaterials(910000000000010005L, "讲义里怎样用互斥锁解释缓存击穿？",
                    new ToolContext(toolCtx));

            assertThat(result.status()).isEqualTo("OK");
            assertThat(result.evidence()).isNotEmpty();
            assertThat(result.evidence()).allSatisfy(e ->
                    assertThat(e.materialId()).startsWith("redis-").doesNotStartWith("mysql-"));
            assertThat(result.evidence().get(0).excerpt()).isNotBlank();

            var denied = tools.searchCourseMaterials(999L, "缓存击穿", new ToolContext(toolCtx));
            assertThat(denied.status()).isEqualTo("ACCESS_DENIED");
        }
    }
}
