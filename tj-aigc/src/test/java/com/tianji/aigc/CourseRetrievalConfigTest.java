package com.tianji.aigc;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tianji.aigc.config.CourseRetrievalConfig;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.course.CourseImportCommand;
import com.tianji.aigc.knowledge.course.CourseSemanticSearch;
import com.tianji.aigc.tools.CourseDetailReader;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CourseRetrievalConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CourseRetrievalConfig.class);

    @Test
    void disabledCourseRetrievalOrDisabledKnowledgeNeedsNoExternalDependencies() {
        runner.withPropertyValues("tj.ai.knowledge.enabled=true", "tj.ai.course-retrieval.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CourseSemanticSearch.class));
        runner.withPropertyValues("tj.ai.knowledge.enabled=false", "tj.ai.course-retrieval.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CourseSemanticSearch.class));
    }

    @Test
    void redisKnowledgeDoesNotCreateAnElasticsearchCourseDependency() {
        runner.withPropertyValues("tj.ai.knowledge.enabled=true", "tj.ai.course-retrieval.enabled=true",
                        "tj.ai.knowledge.store-type=redis")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CourseSemanticSearch.class));
    }

    @Test
    void independentCourseStoreDoesNotReplaceTheKnowledgeVectorBean() throws Exception {
        var factory = mock(KnowledgeStoreConfig.class);
        var rest = mock(RestClient.class);
        var client = mock(ElasticsearchClient.class);
        var embeddings = mock(EmbeddingModel.class);
        var knowledge = mock(ElasticsearchVectorStore.class);
        var courses = mock(ElasticsearchVectorStore.class);
        when(factory.knowledgeVectorStore(eq(rest), eq(client), eq(embeddings), eq(CourseImportCommand.COURSE_INDEX), eq(1024), any())).thenReturn(courses);
        runner.withInitializer(context -> {
            var beans = context.getBeanFactory();
            beans.registerSingleton("knowledgeStoreConfig", factory);
            beans.registerSingleton("rest", rest);
            beans.registerSingleton("client", client);
            beans.registerSingleton("embedding", embeddings);
            beans.registerSingleton("knowledgeVectorStore", knowledge);
        }).withPropertyValues("tj.ai.knowledge.enabled=true", "tj.ai.course-retrieval.enabled=true",
                        "tj.ai.knowledge.embedding.model=text-embedding-v4", "tj.ai.knowledge.embedding.dimensions=1024")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CourseSemanticSearch.class);
                    assertThat(context.getBeansOfType(VectorStore.class)).containsOnlyKeys("knowledgeVectorStore");
                    assertThat(context.getBean(VectorStore.class)).isSameAs(knowledge);
                });
        verifyNoInteractions(knowledge, courses, embeddings);
    }

    @Test
    void toolsCanStartWithOrWithoutTheOptionalSemanticService() {
        var tools = new ApplicationContextRunner().withUserConfiguration(CourseTools.class)
                .withBean(CourseClient.class, () -> mock(CourseClient.class))
                .withBean(SearchClient.class, () -> mock(SearchClient.class))
                .withBean(CourseDetailReader.class, () -> mock(CourseDetailReader.class));
        tools.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CourseTools.class).supportsSemanticSearch()).isFalse();
        });
        tools.withBean(CourseSemanticSearch.class, () -> mock(CourseSemanticSearch.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CourseTools.class).supportsSemanticSearch()).isTrue();
        });
    }
}
