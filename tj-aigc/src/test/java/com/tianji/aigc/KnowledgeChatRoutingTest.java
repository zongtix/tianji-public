package com.tianji.aigc;

import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.service.ChatGenerationStore;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
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
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

class KnowledgeChatRoutingTest {
    private final VectorStore vectors = mock(VectorStore.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ChatModel rewriteModel = mock(ChatModel.class);
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final ChatGenerationStore leases = mock(ChatGenerationStore.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
    private final ChatSession session = new ChatSession();
    private final KnowledgeBaseAccess access = new KnowledgeBaseAccess();
    private ChatGenerationStore.Generation lease;
    private ChatServiceImpl service;
    private ChatClient client;
    private KnowledgeRetrievalAdvisor rag;
    private final com.tianji.aigc.config.KnowledgeRagProperties ragSettings = new com.tianji.aigc.config.KnowledgeRagProperties();

    @BeforeEach
    void setUp() {
        // 路由测试固定纯向量存储；混合检索的双路行为由 KnowledgeRetrievalPipelineTest 覆盖。
        ragSettings.setMode(com.tianji.aigc.config.KnowledgeRagProperties.Mode.A);
        UserContext.setUser(42L);
        session.setUserId(42L);
        access.setEnabled(true);
        access.setPublicBases(Map.of("tianji-points", List.of("tianji-points-guide")));
        when(sessions.requireOwnedSession("session")).thenReturn(session);
        when(sessions.requireOwnedSession(eq("session"), any())).thenReturn(session);
        lease = mock(ChatGenerationStore.Generation.class);
        when(lease.isValid()).thenReturn(true);
        when(leases.acquire("session")).thenReturn(lease);
        when(leases.renew(lease)).thenReturn(true);
        when(leases.isCurrent(lease)).thenReturn(true);
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var config = new SpringAIConfig();
        client = config.chatClient(ChatClient.builder(model), config.loggerAdvisor(),
                config.messageChatMemoryAdvisor(memory), model);
        when(rewriteModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(answer("{\"action\":\"KEEP\",\"query\":\"\"}")));
        var storeConfig = new KnowledgeStoreConfig();
        var beansForPipeline = new DefaultListableBeanFactory();
        var pipeline = storeConfig.knowledgeRetrievalPipeline(vectors, 3, 0.6, "tj_aigc_test",
                beansForPipeline.getBeanProvider(co.elastic.clients.elasticsearch.ElasticsearchClient.class),
                beansForPipeline.getBeanProvider(io.micrometer.observation.ObservationRegistry.class), ragSettings);
        rag = storeConfig.knowledgeAdvisor(vectors, rewriteModel, pipeline, ragSettings);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("knowledgeAdvisor", rag);
        var prompts = mock(SystemPromptConfig.class);
        when(prompts.getChatSystemMessage()).thenReturn(new AtomicReference<>("原系统要求 {now}"));
        service = new ChatServiceImpl(com.tianji.aigc.ChatTestSupport.dispatcher(client, prompts), leases, memory, mock(com.tianji.aigc.service.ChatTitleGenerator.class), sessions, access,
                beans.getBeanProvider(KnowledgeRetrievalAdvisor.class), io.micrometer.observation.ObservationRegistry.NOOP);
    }

    @AfterEach
    void clearUser() { UserContext.removeUser(); }

    @Test
    void deletionBetweenEntryCheckAndSlotAcquisitionPreventsModelInvocation() {
        when(sessions.requireOwnedSession(eq("session"), any()))
                .thenThrow(new BadRequestException("会话不存在或无权访问"));
        var events = service.chat("你好", "session").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("CHAT_GENERATION_FAILED");
        verify(leases).release(lease);
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_session")).isEmpty();
    }

    @Test
    void ordinaryChatNeverRetrievesEvenWhenKnowledgeIsEnabled() {
        memory.add("42_session", new UserMessage("之前聊过签到积分"));
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            assertThat(((Prompt) invocation.getArgument(0)).getUserMessage().getText()).isEqualTo("你好");
            return Flux.just(answer("你好！"));
        });
        var events = service.chat("你好", "session").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1001, 1002);
        verifyNoInteractions(vectors, rewriteModel);
    }

    @Test
    void boundSessionAndFollowupAlwaysUseServerDocumentScope() {
        session.setKnowledgeBaseId("tianji-points");
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("第七天共11分")));
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertThat(prompt.getUserMessage().getText()).contains("第七天共11分");
            assertThat(prompt.getSystemMessage().getText()).contains("原系统要求").doesNotContain("recommendCourses");
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()).isEmpty();
            return Flux.just(answer("11分"));
        });
        service.chat("第七天多少分？", "session").collectList().block(Duration.ofSeconds(5));
        service.chat("为什么？", "session").collectList().block(Duration.ofSeconds(5));
        verify(vectors, times(2)).similaritySearch(argThat((SearchRequest r) ->
                r.getFilterExpression().toString().contains("tianji-points-guide") && r.getTopK() == 3));
        assertThat(memory.get("42_session")).extracting(m -> m.getText())
                .containsExactly("第七天多少分？", "11分", "为什么？", "11分");
    }

    @Test
    void unpublishedKnowledgeBaseIsDeniedBeforeLeaseOrRetrieval() {
        session.setKnowledgeBaseId("private-other-course");
        assertThatThrownBy(() -> service.chat("积分", "session")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(vectors, leases);
    }

    @Test
    void disabledKnowledgeDoesNotSilentlyBecomeOrdinaryChat() {
        session.setKnowledgeBaseId("tianji-points");
        access.setEnabled(false);
        assertThatThrownBy(() -> service.chat("积分", "session"))
                .isInstanceOf(BadRequestException.class).hasMessage("知识库功能未启用");
        verifyNoInteractions(vectors, leases);
    }

    @Test
    void emptyRetrievalProducesExplicitAnswerAndPersistsItWithoutModelFallback() {
        session.setKnowledgeBaseId("tianji-points");
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var events = service.chat("不相关问题", "session").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1001, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("没有找到足够相关的资料");
        assertThat(memory.get("42_session").get(1).getText()).contains("没有找到足够相关的资料");
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void retrievalFailureEmitsSanitizedErrorAndTerminalEventThenReleasesLease() {
        session.setKnowledgeBaseId("tianji-points");
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("internal embedding endpoint details"));
        var events = service.chat("积分", "session").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString())
                .contains("KNOWLEDGE_RETRIEVAL_FAILED").doesNotContain("internal");
        verify(model, never()).stream(any(Prompt.class));
        verify(leases).release(lease);
        assertThat(memory.get("42_session")).hasSize(1);
    }

    @Test
    void stopDuringRetrievalDoesNotStartModelOrEmitLateAnswer() throws Exception {
        session.setKnowledgeBaseId("tianji-points");
        var entered = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            unblock.await(5, TimeUnit.SECONDS);
            return List.of(new Document("稍后返回的知识片段"));
        });
        var result = service.chat("积分", "session").collectList().toFuture();
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            when(leases.renew(lease)).thenReturn(false);
            var events = result.get(4, TimeUnit.SECONDS);
            assertThat(events).extracting(e -> e.getEventType()).containsExactly(1002);
            verify(model, never()).stream(any(Prompt.class));
            verify(leases).release(lease);
            assertThat(memory.get("42_session")).hasSize(1);
        } finally {
            unblock.countDown();
            result.cancel(true);
        }
    }

    @Test
    void advisorCannotRetrieveWithoutExplicitServerScope() {
        assertThatThrownBy(() -> client.prompt().user("积分").advisors(rag)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "scope-test")).call().content())
                .hasMessageContaining("授权文档范围");
        verifyNoInteractions(vectors);
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void firstKnowledgeQuestionSkipsRewrite() {
        session.setKnowledgeBaseId("tianji-points");
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        service.chat("签到可以获得多少积分？", "session").collectList().block(Duration.ofSeconds(5));
        verifyNoInteractions(rewriteModel);
        verify(vectors).similaritySearch(argThat((SearchRequest r) -> r.getQuery().equals("签到可以获得多少积分？")));
    }

    @Test
    void followupRewritesOnlySearchAndKeepsAuthorizationAndHistory() {
        session.setKnowledgeBaseId("tianji-points");
        memory.add("42_session", List.of(new UserMessage("签到可以获得多少积分？"), new AssistantMessage("每天有基础积分。")));
        String rewritten = "连续签到七天，当天总共能获得多少积分？";
        when(rewriteModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            var input = new com.fasterxml.jackson.databind.ObjectMapper().readTree(prompt.getUserMessage().getText());
            assertThat(input.path("currentQuestion").asText()).isEqualTo("那连续七天呢？");
            assertThat(input.path("history")).hasSize(2);
            assertThat(input.path("history").toString()).contains("签到可以获得多少积分").doesNotContain("那连续七天呢");
            var options = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getOptions();
            assertThat(options.getToolChoice()).isEqualTo("none");
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            assertThat(options.getToolCallbacks()).isEmpty();
            assertThat(options.getToolNames()).isEmpty();
            assertThat(prompt.getInstructions()).hasSize(2);
            return Flux.just(answer("{\"action\":\"REWRITE\",\"query\":\"" + rewritten + "\"}"));
        });
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("第七天共11分")));
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertThat(prompt.getUserMessage().getText()).contains("那连续七天呢？", "第七天共11分").doesNotContain(rewritten);
            return Flux.just(answer("11分"));
        });
        service.chat("那连续七天呢？", "session").collectList().block(Duration.ofSeconds(5));
        verify(vectors).similaritySearch(argThat((SearchRequest r) -> r.getQuery().equals(rewritten)
                && r.getTopK() == 3 && r.getSimilarityThreshold() == 0.6
                && r.getFilterExpression().equals(new org.springframework.ai.vectorstore.filter.FilterExpressionTextParser()
                        .parse("documentId in ['tianji-points-guide']"))));
        assertThat(memory.get("42_session")).extracting(m -> m.getText())
                .containsExactly("签到可以获得多少积分？", "每天有基础积分。", "那连续七天呢？", "11分");
    }

    @Test
    void explicitNewTopicUsesOriginalQuestion() {
        session.setKnowledgeBaseId("tianji-points");
        memory.add("42_session", new UserMessage("签到可以获得多少积分？"));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        service.chat("换个话题，课程如何退款？", "session").collectList().block(Duration.ofSeconds(5));
        verify(rewriteModel).stream(any(Prompt.class));
        verify(vectors).similaritySearch(argThat((SearchRequest r) -> r.getQuery().equals("换个话题，课程如何退款？")));
    }

    @Test
    void ambiguousContextAsksForClarificationWithoutRetrievalOrAnswerModel() {
        session.setKnowledgeBaseId("tianji-points");
        memory.add("42_session", new UserMessage("签到和学习视频有不同的积分规则吗？"));
        when(rewriteModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(answer("{\"action\":\"CLARIFY\",\"query\":\"\"}")));
        var events = service.chat("那它呢？", "session").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1001, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("请补充").doesNotContain("没有找到");
        verifyNoInteractions(vectors);
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_session")).extracting(m -> m.getText())
                .containsExactly("签到和学习视频有不同的积分规则吗？", "那它呢？", events.get(0).getEventData().toString());
    }

    @Test
    void rewriteUsesOnlyAuthorizedCurrentConversation() {
        session.setKnowledgeBaseId("tianji-points");
        memory.add("41_session", new UserMessage("其他用户的私密历史"));
        memory.add("42_other", new UserMessage("其他会话的历史"));
        memory.add("42_session", new UserMessage("当前会话的签到问题"));
        when(rewriteModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            assertThat(((Prompt) invocation.getArgument(0)).getUserMessage().getText())
                    .contains("当前会话的签到问题").doesNotContain("其他用户", "其他会话");
            return Flux.just(answer("{\"action\":\"KEEP\",\"query\":\"\"}"));
        });
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        service.chat("第七天呢？", "session").collectList().block(Duration.ofSeconds(5));
        verify(rewriteModel).stream(any(Prompt.class));
        verify(sessions).requireOwnedSession("session");
        when(sessions.requireOwnedSession("foreign")).thenThrow(new BadRequestException("会话不存在或无权访问"));
        assertThatThrownBy(() -> service.chat("追问", "foreign")).isInstanceOf(BadRequestException.class);
        verify(rewriteModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    void rewriteFailureHasItsOwnErrorAndDoesNotPersistAnswerOrFallback() {
        session.setKnowledgeBaseId("tianji-points");
        memory.add("42_session", new UserMessage("签到积分"));
        when(rewriteModel.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("private endpoint")));
        var events = service.chat("第七天呢？", "session").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("KNOWLEDGE_QUERY_REWRITE_FAILED")
                .doesNotContain("KNOWLEDGE_RETRIEVAL_FAILED", "没有找到", "private endpoint");
        verifyNoInteractions(vectors);
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_session")).extracting(m -> m.getText()).containsExactly("签到积分", "第七天呢？");
        verify(leases).release(lease);
    }

    @Test
    void stopDuringRewriteCancelsModelAndDiscardsLateResult() throws Exception {
        session.setKnowledgeBaseId("tianji-points");
        memory.add("42_session", new UserMessage("签到积分"));
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(rewriteModel.stream(any(Prompt.class))).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
        var result = service.chat("第七天呢？", "session").collectList().toFuture();
        await().atMost(Duration.ofSeconds(3)).until(() -> source.currentSubscriberCount() == 1);
        when(leases.renew(lease)).thenReturn(false);
        var events = result.get(4, TimeUnit.SECONDS);
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1002);
        assertThat(cancelled).isTrue();
        source.tryEmitNext(answer("{\"action\":\"REWRITE\",\"query\":\"迟到的问题\"}"));
        source.tryEmitComplete();
        verifyNoInteractions(vectors);
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_session")).extracting(m -> m.getText()).containsExactly("签到积分", "第七天呢？");
        verify(leases).release(lease);
    }

    @Test
    void stopBeforeHeartbeatIsCheckedAgainBeforeRetrieval() throws Exception {
        session.setKnowledgeBaseId("tianji-points");
        memory.add("42_session", new UserMessage("签到积分"));
        when(rewriteModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            when(leases.renew(lease)).thenReturn(false);
            return Flux.just(answer("{\"action\":\"REWRITE\",\"query\":\"连续签到七天的积分\"}"));
        });
        var events = service.chat("第七天呢？", "session").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1002);
        verifyNoInteractions(vectors);
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_session")).hasSize(2);
    }

    @Test
    void disconnectDuringRewriteCancelsWorkAndReleasesLease() {
        session.setKnowledgeBaseId("tianji-points");
        memory.add("42_session", new UserMessage("签到积分"));
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(rewriteModel.stream(any(Prompt.class))).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
        var subscription = service.chat("第七天呢？", "session").subscribe();
        await().atMost(Duration.ofSeconds(3)).until(() -> source.currentSubscriberCount() == 1);
        subscription.dispose();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(leases).release(lease));
        assertThat(cancelled).isTrue();
        verifyNoInteractions(vectors);
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_session")).hasSize(2);
    }

    @Test
    void oversizedNecessaryContextUsesExplicitErrorWithoutStartingRewriteRetrievalOrAnswer() {
        session.setKnowledgeBaseId("tianji-points");
        ragSettings.getContext().setWindowTokens(100);
        var events = service.chat("签到规则", "session").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("KNOWLEDGE_CONTEXT_TOO_LONG");
        verifyNoInteractions(vectors);
        verify(rewriteModel, never()).stream(any(Prompt.class));
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_session")).extracting(m -> m.getText()).containsExactly("签到规则");
    }

    private ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
