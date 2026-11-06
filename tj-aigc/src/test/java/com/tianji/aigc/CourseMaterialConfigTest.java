package com.tianji.aigc;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tianji.aigc.config.CourseMaterialConfig;
import com.tianji.aigc.config.KnowledgeRagProperties;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.material.CourseMaterialSearch;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CourseMaterialConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CourseMaterialConfig.class);

    @Test
    void disabledCourseMaterialsNeedsNoBean() {
        runner.withPropertyValues(
                        "tj.ai.knowledge.enabled=true",
                        "tj.ai.course-materials.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CourseMaterialSearch.class));
        runner.withPropertyValues(
                        "tj.ai.knowledge.enabled=false",
                        "tj.ai.course-materials.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CourseMaterialSearch.class));
    }

    @Test
    void withoutElasticsearchClientsMaterialsSearchIsAbsentAndContextStillStarts() {
        // store-type=redis 时不注册讲义检索 Bean（讲义依赖 ES RestClient），且不能拖垮启动。
        runner.withPropertyValues(
                        "tj.ai.knowledge.enabled=true",
                        "tj.ai.course-materials.enabled=true",
                        "tj.ai.knowledge.store-type=redis")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CourseMaterialSearch.class));
    }

    @Test
    void materialStoreDoesNotReplaceKnowledgeVectorBean() throws Exception {
        var factory = mock(KnowledgeStoreConfig.class);
        var rest = mock(RestClient.class);
        var client = mock(ElasticsearchClient.class);
        var embeddings = mock(EmbeddingModel.class);
        var knowledge = mock(ElasticsearchVectorStore.class);
        var materials = mock(ElasticsearchVectorStore.class);
        when(factory.knowledgeVectorStore(eq(rest), eq(client), eq(embeddings),
                eq(CourseMaterialSearch.INDEX), eq(1024), any())).thenReturn(materials);
        runner.withInitializer(context -> {
            var beans = context.getBeanFactory();
            beans.registerSingleton("knowledgeStoreConfig", factory);
            beans.registerSingleton("rest", rest);
            beans.registerSingleton("client", client);
            beans.registerSingleton("embedding", embeddings);
            beans.registerSingleton("knowledgeVectorStore", knowledge);
            beans.registerSingleton("knowledgeRagProperties", new KnowledgeRagProperties());
        }).withPropertyValues(
                        "tj.ai.knowledge.enabled=true",
                        "tj.ai.course-materials.enabled=true",
                        "tj.ai.knowledge.embedding.model=text-embedding-v4",
                        "tj.ai.knowledge.embedding.dimensions=1024",
                        "tj.ai.course-materials.index-name=" + CourseMaterialSearch.INDEX)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CourseMaterialSearch.class);
                    assertThat(context.getBeansOfType(VectorStore.class)).containsOnlyKeys("knowledgeVectorStore");
                    assertThat(context.getBean(VectorStore.class)).isSameAs(knowledge);
                });
        verifyNoInteractions(knowledge, materials, embeddings);
    }
}
