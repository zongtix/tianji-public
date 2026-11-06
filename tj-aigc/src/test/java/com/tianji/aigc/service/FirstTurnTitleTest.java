package com.tianji.aigc.service;

import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.aigc.vo.ChatEventVO;
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
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FirstTurnTitleTest {
    private final ChatModel model = mock(ChatModel.class);
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final ChatGenerationStore store = mock(ChatGenerationStore.class);
    private final ChatTitleGenerator titles = mock(ChatTitleGenerator.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUser(42L);
        var session = ChatSession.builder().sessionId("session").userId(42L).build();
        when(sessions.requireOwnedSession("session")).thenReturn(session);
        when(sessions.requireOwnedSession("session", 42L)).thenReturn(session);
        when(sessions.claimFirstTurnTitle("session", 42L)).thenReturn(true, false);
        var lease = mock(ChatGenerationStore.Generation.class);
        when(lease.isValid()).thenReturn(true);
        when(store.acquire("session")).thenReturn(lease);
        when(store.renew(lease)).thenAnswer(invocation -> active.get());
        when(store.isCurrent(lease)).thenReturn(true);
        doAnswer(invocation -> { active.set(false); return null; }).when(store).stop("session");
        var prompts = mock(SystemPromptConfig.class);
        when(prompts.getChatSystemMessage()).thenReturn(new AtomicReference<>("test {now}"));
        var client = ChatClient.builder(model).defaultAdvisors(new SpringAIConfig().messageChatMemoryAdvisor(memory)).build();
        service = new ChatServiceImpl(com.tianji.aigc.ChatTestSupport.dispatcher(client, prompts), store, memory, titles, sessions, new KnowledgeBaseAccess(),
                new DefaultListableBeanFactory().getBeanProvider(KnowledgeRetrievalAdvisor.class),
                io.micrometer.observation.ObservationRegistry.NOOP);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(reply("用 Redis "), reply("SET NX 实现分布式锁。")));
        when(titles.generate(anyString(), anyString())).thenReturn(Mono.just("Redis 分布式锁的实现"));
    }

    @AfterEach
    void clear() { UserContext.removeUser(); }

    @Test
    void completedFirstAnswerGeneratesTitleWithoutAddingItToChatOrSse() {
        var events = service.chat("这个怎么实现？", "session").collectList().block(Duration.ofSeconds(4));
        verify(titles).generate("这个怎么实现？", "用 Redis SET NX 实现分布式锁。");
        verify(sessions).saveGeneratedTitle("session", 42L, "Redis 分布式锁的实现");
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1001, 1002);
        assertThat(memory.get("42_session")).extracting(Message::getText)
                .containsExactly("这个怎么实现？", "用 Redis SET NX 实现分布式锁。");
        service.chat("再讲讲", "session").collectList().block(Duration.ofSeconds(4));
        verify(titles, times(1)).generate(anyString(), anyString());
    }

    @Test
    void titleFailureKeepsAnswerAndNormalTerminalEvent() {
        when(titles.generate(anyString(), anyString())).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        var events = service.chat("问题", "session").collectList().block(Duration.ofSeconds(4));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1001, 1002);
        verify(sessions, never()).saveGeneratedTitle(any(), any(), any());
    }

    @Test
    void modelFailureDoesNotGenerateTitle() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("answer failed")));
        var events = service.chat("问题", "session").collectList().block(Duration.ofSeconds(4));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("CHAT_GENERATION_FAILED");
        verifyNoInteractions(titles);
    }

    @Test
    void stoppingPartialAnswerSkipsTitle() throws Exception {
        var source = Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        when(model.stream(any(Prompt.class))).thenReturn(source.asFlux());
        var result = service.chat("问题", "session").collectList().toFuture();
        await().atMost(Duration.ofSeconds(3)).until(() -> source.currentSubscriberCount() == 1);
        source.tryEmitNext(reply("部分回答")).orThrow();
        service.stop("session");
        assertThat(result.get(4, TimeUnit.SECONDS)).extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
        verifyNoInteractions(titles);
    }

    @Test
    void stoppingDuringTitleCancelsItAndKeepsTemporaryTitle() throws Exception {
        AtomicBoolean started = new AtomicBoolean(), cancelled = new AtomicBoolean();
        when(titles.generate(anyString(), anyString())).thenReturn(Mono.<String>never()
                .doOnSubscribe(s -> started.set(true)).doOnCancel(() -> cancelled.set(true)));
        var result = service.chat("问题", "session").collectList().toFuture();
        await().atMost(Duration.ofSeconds(3)).untilTrue(started);
        service.stop("session");
        assertThat(result.get(4, TimeUnit.SECONDS)).extracting(ChatEventVO::getEventType).containsExactly(1001, 1001, 1002);
        assertThat(cancelled).isTrue();
        verify(sessions, never()).saveGeneratedTitle(any(), any(), any());
    }

    @Test
    void disconnectDuringTitleCancelsModelAndReleasesLease() {
        AtomicBoolean started = new AtomicBoolean(), cancelled = new AtomicBoolean();
        when(titles.generate(anyString(), anyString())).thenReturn(Mono.<String>never()
                .doOnSubscribe(s -> started.set(true)).doOnCancel(() -> cancelled.set(true)));
        var subscription = service.chat("问题", "session").subscribe();
        await().atMost(Duration.ofSeconds(3)).untilTrue(started);
        subscription.dispose();
        await().atMost(Duration.ofSeconds(3)).untilTrue(cancelled);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(store).release(any()));
        verify(sessions, never()).saveGeneratedTitle(any(), any(), any());
    }

    private ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
