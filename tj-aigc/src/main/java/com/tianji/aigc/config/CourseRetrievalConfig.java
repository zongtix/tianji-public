package com.tianji.aigc.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tianji.aigc.knowledge.course.CourseImportCommand;
import com.tianji.aigc.knowledge.course.CourseSemanticSearch;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tj.ai", name = {"knowledge.enabled", "course-retrieval.enabled"}, havingValue = "true")
public class CourseRetrievalConfig {
    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "store-type", havingValue = "elasticsearch", matchIfMissing = true)
    public CourseSemanticSearch courseSemanticSearch(KnowledgeStoreConfig factory, RestClient rest,
            ElasticsearchClient client, EmbeddingModel embeddings,
            @Value("${tj.ai.knowledge.embedding.model}") String model,
            @Value("${tj.ai.knowledge.embedding.dimensions}") int dimensions,
            @Value("${tj.ai.course-retrieval.top-k:5}") int topK,
            @Value("${tj.ai.course-retrieval.similarity-threshold:0.6}") double threshold,
            ObjectProvider<ObservationRegistry> observations) throws IOException {
        Assert.isTrue("text-embedding-v4".equals(model) && dimensions == 1024,
                "课程索引只支持 text-embedding-v4 / 1024 维，请使用匹配的模型和索引");
        // 只暴露课程检索类型，避免第二个 VectorStore Bean 改变原知识库的注入目标。
        var vectors = factory.knowledgeVectorStore(rest, client, embeddings, CourseImportCommand.COURSE_INDEX, dimensions, observations);
        return new CourseSemanticSearch(vectors, topK, threshold);
    }
}
