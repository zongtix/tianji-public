package com.tianji.aigc;

import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.config.SpringAIConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeChatClientTest {
    private final VectorStore store = mock(VectorStore.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ChatModel rewriteModel = mock(ChatModel.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
    private final String question = "连续签到第七天一共能拿多少分？";

    @Test
    void retrievesForOriginalQuestionAndKeepsContextOutOfStoredUserMessage() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("天机学堂积分规则 / 每日签到：第七天基础1分加奖励10分，共11分。")));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertThat(prompt.getSystemMessage().getText()).isEqualTo("原系统要求");
            assertThat(prompt.getUserMessage().getText()).contains(question, "共11分", "文档标题和章节");
            return response("第七天共11分，来源：天机学堂积分规则 / 每日签到。");
        });

        var answer = ragRequest().system("原系统要求").user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "rag-call")).call().chatResponse();

        assertThat(answer.getResult().getOutput().getText()).contains("11分");
        assertThat((List<?>) answer.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS)).hasSize(1);
        verify(store).similaritySearch(argThat((SearchRequest r) ->
                r.getQuery().equals(question) && r.getTopK() == 3 && r.getSimilarityThreshold() == 0.6));
        assertThat(memory.get("rag-call").get(0).getText()).isEqualTo(question);
        assertThat(memory.get("rag-call").get(1).getText()).contains("11分");
    }

    @Test
    void streamingUsesRetrievedContextAndStoresOriginalQuestion() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("第七天共11分")));
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertThat(prompt.getUserMessage().getText()).contains(question, "第七天共11分");
            return Flux.just(response("共"), response("11分"));
        });

        var chunks = ragRequest().user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "rag-stream"))
                .stream().content().collectList().block(Duration.ofSeconds(5));

        assertThat(chunks).containsExactly("共", "11分");
        assertThat(memory.get("rag-stream").get(0).getText()).isEqualTo(question);
        assertThat(memory.get("rag-stream").get(1).getText()).isEqualTo("共11分");
        verify(store).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void emptyRetrievalReturnsInsufficientContextWithoutCallingModel() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        assertThat(ragRequest().user("你好").advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "empty-result")).call().content()).contains("没有找到足够相关的资料");
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void disabledKnowledgeDoesNotRequireVectorStoreAdvisor() {
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertThat(prompt.getUserMessage().getText()).isEqualTo("你好");
            return response("你好！");
        });
        assertThat(client().prompt().user("你好").advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "disabled-rag")).call().content()).isEqualTo("你好！");
        verifyNoInteractions(store);
    }

    @Test
    void synchronousFollowupUsesSeparateSearchTextAndOriginalStoredMessage() {
        memory.add("rag-followup", List.of(new UserMessage("签到有多少积分？"), new AssistantMessage("每日基础1分。")));
        when(rewriteModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("{\"action\":\"REWRITE\",\"query\":\"连续签到七天当天的总积分\"}")));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("第七天共11分")));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            assertThat(((Prompt) invocation.getArgument(0)).getUserMessage().getText())
                    .contains("那连续七天呢？", "第七天共11分").doesNotContain("连续签到七天当天的总积分");
            return response("11分");
        });
        assertThat(ragRequest().user("那连续七天呢？")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "rag-followup")).call().content()).isEqualTo("11分");
        verify(store).similaritySearch(argThat((SearchRequest r) -> r.getQuery().equals("连续签到七天当天的总积分")));
        assertThat(memory.get("rag-followup")).extracting(m -> m.getText())
                .containsExactly("签到有多少积分？", "每日基础1分。", "那连续七天呢？", "11分");
    }

    private ChatClient.ChatClientRequestSpec ragRequest() {
        // 此夹具只提供向量存储；混合检索的双路行为由管线测试覆盖。
        var settings = new com.tianji.aigc.config.KnowledgeRagProperties();
        settings.setMode(com.tianji.aigc.config.KnowledgeRagProperties.Mode.A);
        var storeConfig = new KnowledgeStoreConfig();
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var pipeline = storeConfig.knowledgeRetrievalPipeline(store, 3, 0.6, "tj_aigc_test",
                beans.getBeanProvider(co.elastic.clients.elasticsearch.ElasticsearchClient.class),
                beans.getBeanProvider(io.micrometer.observation.ObservationRegistry.class), settings);
        return client().prompt().advisors(storeConfig.knowledgeAdvisor(store, rewriteModel, pipeline, settings))
                .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION,
                        "documentId in ['tianji-points-guide']"));
    }

    private ChatClient client() {
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var config = new SpringAIConfig();
        return config.chatClient(ChatClient.builder(model), config.loggerAdvisor(),
                config.messageChatMemoryAdvisor(memory), model);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
