package com.tianji.aigc.service;

import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.service.impl.ChatSessionServiceImpl;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.aigc.vo.ChatEventVO;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.RedisConnectionFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatStopMemoryTest {
    private static final String SESSION = "stop-memory";
    private static final String CONVERSATION = "42_" + SESSION;
    private ChatMemory memory;
    private ChatGenerationStore store;
    private ChatModel model;
    private ChatServiceImpl service;
    private AtomicBoolean stopRequested;
    private AtomicBoolean occupied;

    @BeforeEach
    void setUp() {
        UserContext.setUser(42L);
        memory = spy(MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).build());
        store = mock(ChatGenerationStore.class);
        ChatGenerationStore.Generation generation = mock(ChatGenerationStore.Generation.class);
        when(generation.isValid()).thenReturn(true);
        stopRequested = new AtomicBoolean();
        occupied = new AtomicBoolean();
        when(store.acquire(SESSION)).thenAnswer(invocation -> {
            if (!occupied.compareAndSet(false, true)) throw new BadRequestException("busy");
            stopRequested.set(false);
            return generation;
        });
        doAnswer(invocation -> {
            occupied.set(false);
            return null;
        }).when(store).release(generation);
        when(store.renew(generation)).thenAnswer(invocation -> !stopRequested.get());
        when(store.isCurrent(generation)).thenAnswer(invocation -> occupied.get());
        doAnswer(invocation -> {
            stopRequested.set(true);
            return null;
        }).when(store).stop(SESSION);
        model = mock(ChatModel.class);
        SpringAIConfig config = new SpringAIConfig();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(config.messageChatMemoryAdvisor(memory))
                .build();
        SystemPromptConfig prompts = mock(SystemPromptConfig.class);
        when(prompts.getChatSystemMessage()).thenReturn(new AtomicReference<>("test system"));
        service = com.tianji.aigc.ChatTestSupport.ordinaryChat(client, prompts, store, memory);
    }

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @Test
    void mcpStopBeforeHeartbeatEndsWithStopAndReleasesGeneration() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("tool wrapper",
                new com.tianji.aigc.mcp.McpCallScope.CancelledException())));
        var events = service.chat("course details", SESSION).collectList().block(Duration.ofSeconds(3));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1002);
        verify(store).release(any());
        assertThat(occupied).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.tianji.aigc.mcp.McpToolException.Kind.class)
    void mcpFailuresKeepErrorAndStopContractAndReleaseTheGeneration(com.tianji.aigc.mcp.McpToolException.Kind kind) {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("tool wrapper",
                new com.tianji.aigc.mcp.McpToolException(kind))));
        var events = service.chat("course details", SESSION).collectList().block(Duration.ofSeconds(3));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("MCP_TOOL_" + kind.name())
                .doesNotContain("tool wrapper", "Exception");
        verify(store).release(any());
        assertThat(occupied).isFalse();
    }

    @Test
    void acknowledgedGenerationCanBeStoppedWithoutWaitingForFirstModelChunk() throws Exception {
        String requestId = "123e4567-e89b-42d3-a456-426614174000";
        var generation = mock(ChatGenerationStore.Generation.class);
        when(generation.isValid()).thenReturn(true);
        when(generation.requestId()).thenReturn(requestId);
        when(store.acquire(SESSION)).thenReturn(generation);
        when(store.renew(generation)).thenAnswer(invocation -> !stopRequested.get());
        when(store.isCurrent(generation)).thenReturn(true);
        when(store.stop(SESSION, requestId)).thenAnswer(invocation -> { stopRequested.set(true); return true; });
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
        var events = new java.util.concurrent.CopyOnWriteArrayList<ChatEventVO>();
        var terminal = new CountDownLatch(1);
        var subscription = service.chat("question", SESSION, true).doFinally(signal -> terminal.countDown()).subscribe(events::add);
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> !events.isEmpty());
            assertThat(events.get(0).getEventType()).isEqualTo(1003);
            assertThat(events.get(0).getEventData()).isEqualTo(java.util.Map.of("state", "ready", "requestId", requestId));
            assertThat(service.stop(SESSION, requestId)).isTrue();
            assertThat(terminal.await(4, TimeUnit.SECONDS)).isTrue();
            assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1003, 1002);
        } finally { subscription.dispose(); }
    }

    @Test
    void slowHeartbeatDoesNotOverflowOrCancelTheModel() throws Exception {
        var source = Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        when(model.stream(any(Prompt.class))).thenReturn(source.asFlux());
        var result = service.chat("explain Java", SESSION).collectList().toFuture();
        await().atMost(Duration.ofSeconds(3)).until(() -> source.currentSubscriberCount() == 1);

        var renewalStarted = new CountDownLatch(1);
        var renewalFinished = new CountDownLatch(1);
        when(store.renew(any())).thenAnswer(invocation -> {
            renewalStarted.countDown();
            // 比心跳周期慢；旧的 interval + concatMap 会在这期间因没有下游请求而溢出。
            Thread.sleep(2500);
            renewalFinished.countDown();
            return !stopRequested.get();
        });
        assertThat(renewalStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(renewalFinished.await(4, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isNotDone();
        assertThat(source.currentSubscriberCount()).isEqualTo(1);

        when(store.renew(any())).thenAnswer(invocation -> !stopRequested.get());
        source.tryEmitNext(chunk("still running")).orThrow();
        source.tryEmitComplete().orThrow();
        assertThat(result.get(4, TimeUnit.SECONDS)).extracting(ChatEventVO::getEventType)
                .containsExactly(1001, 1002);
    }

    @Test
    void stoppedAnswerSurvivesInHistoryBeforeLeaseIsReleased() throws Exception {
        doAnswer(invocation -> {
            assertThat(memory.get(CONVERSATION)).extracting(Message::getText)
                    .containsExactly("explain Java", "partial answer");
            assertThat(MessageUtil.isStopped(memory.get(CONVERSATION).get(1))).isTrue();
            occupied.set(false);
            return null;
        }).when(store).release(any());
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean modelCancelled = new AtomicBoolean();
        when(model.stream(any(Prompt.class)))
                .thenReturn(source.asFlux().doOnCancel(() -> modelCancelled.set(true)));
        var result = service.chat("explain Java", SESSION).collectList().toFuture();
        await().atMost(Duration.ofSeconds(5)).until(() -> source.currentSubscriberCount() == 1);
        source.tryEmitNext(chunk("partial answer")).orThrow();
        service.stop(SESSION);

        List<ChatEventVO> events = result.get(5, TimeUnit.SECONDS);
        assertThat(modelCancelled).isTrue();
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText)
                .containsExactly("explain Java", "partial answer");
        verify(store).release(any());
    }

    @Test
    void stoppedAnswerIsVisibleInHistoryAndAvailableToNextPrompt() throws Exception {
        Sinks.Many<ChatResponse> source = source();
        var result = service.chat("first question", SESSION).collectList().toFuture();
        awaitModel(source);
        source.tryEmitNext(chunk("unfinished text")).orThrow();
        service.stop(SESSION);
        result.get(5, TimeUnit.SECONDS);

        var historyService = spy(new ChatSessionServiceImpl(new SessionProperties(), memory, new com.tianji.aigc.knowledge.KnowledgeBaseAccess(), mock(com.tianji.aigc.service.ChatGenerationStore.class), mock(org.springframework.transaction.PlatformTransactionManager.class)));
        doReturn(com.tianji.aigc.entity.ChatSession.builder().userId(42L).build())
                .when(historyService).requireOwnedSession(SESSION);
        var history = historyService.queryBySessionId(SESSION);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).getContent()).isEqualTo("unfinished text");
        assertThat(history.get(1).getParams()).containsEntry(MessageUtil.GENERATION_STATUS, MessageUtil.STOPPED);

        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("complete reply")));
        service.chat("next question", SESSION).blockLast(Duration.ofSeconds(5));
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).stream(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions()).extracting(Message::getText)
                .contains("first question", "unfinished text", "next question");
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText)
                .containsExactly("first question", "unfinished text", "next question", "complete reply");
    }

    @Test
    void normalCompletionSavesExactlyOneCompleteAnswer() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("first "), chunk("second")));
        service.chat("question", SESSION).blockLast(Duration.ofSeconds(5));
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText)
                .containsExactly("question", "first second");
        assertThat(MessageUtil.isStopped(memory.get(CONVERSATION).get(1))).isFalse();
        verify(store).release(any());
    }

    @Test
    void stopRacingWithAnAlreadySavedCompletionDoesNotAppendDuplicate() throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch finishCompletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(1);
            Object result = invocation.callRealMethod();
            if (messages.stream().anyMatch(message -> message instanceof AssistantMessage
                    && !MessageUtil.isStopped(message))) {
                saved.countDown();
                if (!finishCompletion.await(5, TimeUnit.SECONDS)) throw new AssertionError("completion timed out");
            }
            return result;
        }).when(memory).add(anyString(), anyList());
        Sinks.Many<ChatResponse> source = source();
        var result = service.chat("question", SESSION).collectList().toFuture();
        awaitModel(source);
        source.tryEmitNext(chunk("answer")).orThrow();
        var completing = java.util.concurrent.CompletableFuture.runAsync(() -> source.tryEmitComplete().orThrow());
        try {
            assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
            service.stop(SESSION);
            result.get(5, TimeUnit.SECONDS);
            assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question", "answer");
            assertThat(MessageUtil.isStopped(memory.get(CONVERSATION).get(1))).isFalse();
        } finally {
            finishCompletion.countDown();
        }
        completing.get(5, TimeUnit.SECONDS);
        verify(store).release(any());
    }

    @Test
    void stoppingBeforeFirstTokenDoesNotSaveEmptyAssistant() throws Exception {
        Sinks.Many<ChatResponse> source = source();
        var result = service.chat("question", SESSION).collectList().toFuture();
        awaitModel(source);
        service.stop(SESSION);
        assertThat(result.get(5, TimeUnit.SECONDS)).extracting(ChatEventVO::getEventType).containsExactly(1002);
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question");
    }

    @Test
    void modelFailureDoesNotSavePartialAsStoppedAnswer() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(chunk("partial")), Flux.error(new IllegalStateException("model failed"))));
        assertGenerationFailed(service.chat("question", SESSION).collectList().block(Duration.ofSeconds(5)), true);
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question");
        verify(store).release(any());
    }

    @Test
    void coordinationFailureCancelsModelWithoutSavingPartial() throws Exception {
        Sinks.Many<ChatResponse> source = source();
        var result = service.chat("question", SESSION).collectList().toFuture();
        awaitModel(source);
        source.tryEmitNext(chunk("partial")).orThrow();
        // 先进入回答阶段，再模拟心跳失败；阶段入口失败应当直接阻止模型启动。
        when(store.renew(any())).thenThrow(new RedisConnectionFailureException("redis unavailable"));
        assertGenerationFailed(result.get(5, TimeUnit.SECONDS), true);
        assertThat(source.currentSubscriberCount()).isZero();
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question");
        verify(store).release(any());
    }

    @Test
    void lostLeaseDoesNotWriteOldPartialAnswer() throws Exception {
        Sinks.Many<ChatResponse> source = source();
        when(store.isCurrent(any())).thenReturn(false);
        var result = service.chat("question", SESSION).collectList().toFuture();
        awaitModel(source);
        source.tryEmitNext(chunk("old partial")).orThrow();
        when(store.renew(any())).thenReturn(false);
        assertThat(result.get(5, TimeUnit.SECONDS)).extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
        assertThat(source.currentSubscriberCount()).isZero();
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question");
    }

    @Test
    void disconnectSavesPartialBeforeReleasingLease() {
        Sinks.Many<ChatResponse> source = source();
        CountDownLatch received = new CountDownLatch(1);
        var request = service.chat("question", SESSION).subscribe(event -> received.countDown());
        awaitModel(source);
        source.tryEmitNext(chunk("partial")).orThrow();
        await().until(() -> received.getCount() == 0);
        request.dispose();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).release(any()));
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question", "partial");
        assertThat(MessageUtil.isStopped(memory.get(CONVERSATION).get(1))).isTrue();
    }

    @Test
    void disconnectBeforeFirstTokenDoesNotSaveEmptyAssistant() {
        Sinks.Many<ChatResponse> source = source();
        var request = service.chat("question", SESSION).subscribe();
        awaitModel(source);
        request.dispose();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).release(any()));
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question");
    }

    @Test
    void disconnectWaitsForPersistenceBeforeReleasingLease() throws Exception {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(1);
            if (messages.stream().anyMatch(MessageUtil::isStopped)) {
                saving.countDown();
                if (!allowSave.await(5, TimeUnit.SECONDS)) throw new AssertionError("save timed out");
            }
            return invocation.callRealMethod();
        }).when(memory).add(anyString(), anyList());
        Sinks.Many<ChatResponse> source = source();
        CountDownLatch received = new CountDownLatch(1);
        var request = service.chat("question", SESSION).subscribe(event -> received.countDown());
        awaitModel(source);
        source.tryEmitNext(chunk("partial")).orThrow();
        await().until(() -> received.getCount() == 0);
        request.dispose();
        try {
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
            verify(store, never()).release(any());
            assertGenerationFailed(service.chat("next", SESSION).collectList().block(Duration.ofSeconds(5)), false);
        } finally {
            allowSave.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).release(any()));
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question", "partial");
    }

    @Test
    void disconnectDuringStopPersistenceSharesOneUninterruptedSave() throws Exception {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(1);
            if (messages.stream().anyMatch(MessageUtil::isStopped)) {
                saving.countDown();
                if (!allowSave.await(5, TimeUnit.SECONDS)) throw new AssertionError("save timed out");
            }
            return invocation.callRealMethod();
        }).when(memory).add(anyString(), anyList());
        Sinks.Many<ChatResponse> source = source();
        var result = service.chat("question", SESSION).collectList().toFuture();
        awaitModel(source);
        source.tryEmitNext(chunk("partial")).orThrow();
        service.stop(SESSION);
        try {
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
            result.cancel(true);
            verify(store, never()).release(any());
        } finally {
            allowSave.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).release(any()));
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question", "partial");
        verify(memory, times(1)).add(eq(CONVERSATION), argThat((List<Message> messages) ->
                messages.stream().anyMatch(MessageUtil::isStopped)));
    }

    @Test
    void failedDisconnectSaveStillReleasesLease() {
        doAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(1);
            if (messages.stream().anyMatch(MessageUtil::isStopped)) {
                throw new RedisConnectionFailureException("save failed");
            }
            return invocation.callRealMethod();
        }).when(memory).add(anyString(), anyList());
        Sinks.Many<ChatResponse> source = source();
        CountDownLatch received = new CountDownLatch(1);
        var request = service.chat("question", SESSION).subscribe(event -> received.countDown());
        awaitModel(source);
        source.tryEmitNext(chunk("partial")).orThrow();
        await().until(() -> received.getCount() == 0);
        request.dispose();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).release(any()));
        assertThat(memory.get(CONVERSATION)).extracting(Message::getText).containsExactly("question");
    }

    @Test
    void persistenceFinishesBeforeAnotherGenerationCanStart() throws Exception {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(1);
            if (messages.stream().anyMatch(MessageUtil::isStopped)) {
                saving.countDown();
                if (!allowSave.await(5, TimeUnit.SECONDS)) throw new AssertionError("save timed out");
            }
            return invocation.callRealMethod();
        }).when(memory).add(anyString(), anyList());
        Sinks.Many<ChatResponse> source = source();
        var result = service.chat("question", SESSION).collectList().toFuture();
        awaitModel(source);
        source.tryEmitNext(chunk("partial")).orThrow();
        service.stop(SESSION);
        try {
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
            verify(store, never()).release(any());
            assertGenerationFailed(service.chat("next", SESSION).collectList().block(Duration.ofSeconds(5)), false);
        } finally {
            allowSave.countDown();
        }
        result.get(5, TimeUnit.SECONDS);
        assertThat(memory.get(CONVERSATION)).hasSize(2);
        verify(store).release(any());
    }

    @Test
    void failedPartialSaveEmitsErrorBeforeStop() throws Exception {
        doAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(1);
            if (messages.stream().anyMatch(MessageUtil::isStopped)) {
                throw new RedisConnectionFailureException("save failed");
            }
            return invocation.callRealMethod();
        }).when(memory).add(anyString(), anyList());
        Sinks.Many<ChatResponse> source = source();
        var events = new java.util.concurrent.CopyOnWriteArrayList<ChatEventVO>();
        var result = service.chat("question", SESSION).doOnNext(events::add).collectList().toFuture();
        awaitModel(source);
        source.tryEmitNext(chunk("partial")).orThrow();
        service.stop(SESSION);
        assertGenerationFailed(result.get(5, TimeUnit.SECONDS), true);
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1005, 1002);
        verify(store).release(any());
    }

    private void assertGenerationFailed(List<ChatEventVO> events, boolean partial) {
        assertThat(events).extracting(ChatEventVO::getEventType)
                .containsExactlyElementsOf(partial ? List.of(1001, 1005, 1002) : List.of(1005, 1002));
        assertThat(events.get(partial ? 1 : 0).getEventData().toString()).contains("CHAT_GENERATION_FAILED");
    }

    private Sinks.Many<ChatResponse> source() {
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(model.stream(any(Prompt.class))).thenReturn(source.asFlux());
        return source;
    }

    private void awaitModel(Sinks.Many<ChatResponse> source) {
        await().atMost(Duration.ofSeconds(5)).until(() -> source.currentSubscriberCount() == 1);
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
