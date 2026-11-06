package com.tianji.aigc.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tianji.aigc.knowledge.KnowledgeContextSelector;
import com.tianji.aigc.knowledge.KnowledgeKeywordSearch;
import com.tianji.aigc.knowledge.KnowledgeReranker;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.RagTelemetry;
import com.tianji.aigc.knowledge.material.CourseMaterialSearch;
import io.micrometer.observation.ObservationRegistry;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tj.ai", name = {"knowledge.enabled", "course-materials.enabled"}, havingValue = "true")
public class CourseMaterialConfig {

    /**
     * 讲义索引固定走 ES。用 store-type=elasticsearch 约束（参数注入依赖 RestClient），
     * 避免普通 {@code @Configuration} 上 {@code @ConditionalOnBean(RestClient)} 在客户端尚未注册时被误判跳过。
     * 知识库切 Redis 且仍要讲义时，需另行提供 ES 客户端后再放开本条件。
     */
    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "store-type", havingValue = "elasticsearch",
            matchIfMissing = true)
    public CourseMaterialSearch courseMaterialSearch(KnowledgeStoreConfig factory, RestClient rest,
            ElasticsearchClient client, EmbeddingModel embeddings, KnowledgeRagProperties settings,
            ObjectProvider<ElasticsearchClient> clients, ObjectProvider<ObservationRegistry> observations,
            @Value("${tj.ai.knowledge.embedding.model}") String model,
            @Value("${tj.ai.knowledge.embedding.dimensions}") int dimensions,
            @Value("${tj.ai.course-materials.index-name:" + CourseMaterialSearch.INDEX + "}") String index,
            @Value("${tj.ai.knowledge.retrieval.top-k:3}") int topK,
            @Value("${tj.ai.knowledge.retrieval.similarity-threshold:0.6}") double threshold) throws IOException {
        Assert.isTrue("text-embedding-v4".equals(model) && dimensions == 1024,
                "讲义索引只支持 text-embedding-v4 / 1024 维，请使用匹配的模型和索引");
        Assert.isTrue(CourseMaterialSearch.INDEX.equals(index) || index.startsWith("tj_aigc_"),
                "讲义索引必须使用 tj_aigc_ 前缀");
        // 只暴露 CourseMaterialSearch，避免第二个 VectorStore Bean 改变原知识库的注入目标。
        var vectors = factory.knowledgeVectorStore(rest, client, embeddings, index, dimensions, observations);
        var search = SearchRequest.builder().topK(topK).similarityThreshold(threshold).build();
        var telemetry = new RagTelemetry(observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        var selector = new KnowledgeContextSelector(settings.getContext());
        var keywords = new KnowledgeKeywordSearch(() -> {
            Assert.state(vectors instanceof ElasticsearchVectorStore, "混合检索需要 Elasticsearch 讲义存储");
            return clients.getObject();
        }, index);
        var pipeline = new KnowledgeRetrievalPipeline(vectors, keywords,
                new KnowledgeReranker(settings.getRerank(), selector), search, settings, telemetry);
        return new CourseMaterialSearch(pipeline);
    }
}
