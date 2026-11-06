package com.tianji.aigc;

import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import com.tianji.aigc.knowledge.KnowledgeDocumentService;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class KnowledgeImportContextTest {
    @Test
    void standaloneImportContextNeedsNoChatModel() throws Exception {
        var vectors = mock(ElasticsearchVectorStore.class);
        var embeddings = mock(EmbeddingModel.class);
        try (var context = importContext(vectors, embeddings)) {
            context.refresh();
            assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
            assertThat(context.getBean(KnowledgeRetrievalAdvisor.class)).isNotNull();
            var imported = context.getBean(KnowledgeDocumentService.class).importMarkdown(
                    new ClassPathResource("knowledge/tianji-points.md"), "tianji-points-guide", "积分规则");
            assertThat(imported.imported()).isTrue();
            assertThat(imported.chunks()).isPositive();
            verify(vectors).add(anyList());
            verifyNoInteractions(embeddings);
        }
    }

    @Test
    void chatModelIsResolvedOnlyWhenHistoryRequiresRewrite() {
        var vectors = mock(ElasticsearchVectorStore.class);
        var embeddings = mock(EmbeddingModel.class);
        var model = mock(ChatModel.class);
        var creations = new AtomicInteger();
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"action\":\"REWRITE\",\"query\":\"连续签到七天的积分\"}"))))));
        try (var context = importContext(vectors, embeddings)) {
            context.registerBean("chatModel", ChatModel.class, () -> {
                creations.incrementAndGet();
                return model;
            }, definition -> definition.setLazyInit(true));
            context.refresh();
            var advisor = context.getBean(KnowledgeRetrievalAdvisor.class);
            assertThat(creations).hasValue(0);
            advisor.before(request("签到积分"), null);
            assertThat(creations).hasValue(0);
            advisor.before(request("签到积分", "那连续七天呢？"), null);
            assertThat(creations).hasValue(1);
            verify(model).stream(any(Prompt.class));
            verify(vectors).similaritySearch(argThat((SearchRequest search) ->
                    search.getQuery().equals("连续签到七天的积分")));
        }
    }

    private AnnotationConfigApplicationContext importContext(ElasticsearchVectorStore vectors, EmbeddingModel embeddings) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("knowledge-import-test", Map.of(
                "tj.ai.knowledge.enabled", true, "tj.ai.knowledge.embedding.model", "test-embedding")));
        // 与导入入口注册相同配置，只替换会访问存储的 Bean，不提供 ChatModel。
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("knowledgeVectorStore", vectors);
            factory.registerSingleton("dashscopeEmbeddingModel", embeddings);
            factory.registerSingleton("elasticsearchDocumentIndex", mock(KnowledgeDocumentIndex.class));
        });
        context.register(JacksonAutoConfiguration.class, KnowledgeStoreConfig.class, KnowledgeDocumentService.class);
        return context;
    }

    private ChatClientRequest request(String... questions) {
        return new ChatClientRequest(new Prompt(java.util.Arrays.stream(questions)
                .map(question -> (org.springframework.ai.chat.messages.Message) new UserMessage(question)).toList()),
                Map.of(QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId in ['tianji-points-guide']"));
    }
}
