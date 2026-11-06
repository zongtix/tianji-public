package com.tianji.aigc.service;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.memory.RedisChatMemoryRepository;
import com.tianji.aigc.memory.MessageUtil;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.exceptions.BadRequestException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 两套独立 Redis 连接和服务实例，只访问带测试标记的临时 Redis。 */
@EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_REDIS_PORT", matches = "\\d+")
@Timeout(15)
class ChatRedisCoordinationTest {
    private static final String MARKER = "codex:aigc:test-instance";
    private static LettuceConnectionFactory factoryA;
    private static LettuceConnectionFactory factoryB;
    private static StringRedisTemplate redisA;
    private static StringRedisTemplate redisB;
    private ChatGenerationStore storeA;
    private ChatGenerationStore storeB;
    private ChatServiceImpl serviceA;
    private ChatServiceImpl serviceB;
    private ChatClient.StreamResponseSpec streamA;
    private ChatClient.StreamResponseSpec streamB;
    private String session;
    private final List<Disposable> subscriptions = new ArrayList<>();

    @BeforeAll
    static void connect() {
        int port = Integer.parseInt(System.getenv("TJ_AIGC_TEST_REDIS_PORT"));
        factoryA = new LettuceConnectionFactory("127.0.0.1", port);
        factoryB = new LettuceConnectionFactory("127.0.0.1", port);
        factoryA.afterPropertiesSet();
        factoryB.afterPropertiesSet();
        redisA = new StringRedisTemplate(factoryA);
        redisB = new StringRedisTemplate(factoryB);
        assertThat(redisA.opsForValue().get(MARKER)).isEqualTo("aigc-coordination-test");
        assertThat(redisB.opsForValue().get(MARKER)).isEqualTo("aigc-coordination-test");
    }

    @AfterAll
    static void disconnect() {
        if (factoryA != null) factoryA.destroy();
        if (factoryB != null) factoryB.destroy();
    }

    @BeforeEach
    void setUp() {
        session = "test-" + UUID.randomUUID();
        storeA = spy(new ChatGenerationStore(redisA));
        storeB = new ChatGenerationStore(redisB);
        streamA = mock(ChatClient.StreamResponseSpec.class);
        streamB = mock(ChatClient.StreamResponseSpec.class);
        serviceA = service(storeA, streamA, redisA);
        serviceB = service(storeB, streamB, redisB);
    }

    @AfterEach
    void cleanUp() {
        subscriptions.forEach(Disposable::dispose);
        // 只删除本用例的随机会话键，不清库、不访问业务 Redis。
        if (!subscriptions.isEmpty()) {
            await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redisA.hasKey(key())));
        }
        redisA.delete(key());
        redisA.delete(historyKey());
    }

    @Test
    void requestBoundStopCannotCancelAnotherGeneration() {
        var generation = storeA.acquire(session);
        try {
            assertThat(storeB.stop(session, UUID.randomUUID().toString())).isFalse();
            assertThat(storeA.renew(generation)).isTrue();
            assertThat(storeB.stop(session, generation.requestId())).isTrue();
            assertThat(storeA.renew(generation)).isFalse();
        } finally { storeA.release(generation); }
    }

    @Test
    void secondInstanceCannotGenerateForAnOccupiedSession() {
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(streamA.chatResponse()).thenReturn(source.asFlux());
        List<ChatEventVO> events = new CopyOnWriteArrayList<>();
        subscriptions.add(serviceA.chat("first", session).subscribe(events::add));
        await().until(() -> source.currentSubscriberCount() == 1);

        assertBusy(serviceB);
        verifyNoInteractions(streamB);
        source.tryEmitNext(chunk("仍在生成")).orThrow();
        await().untilAsserted(() -> assertThat(events).extracting(ChatEventVO::getEventData)
                .containsExactly("仍在生成"));
    }

    @Test
    void stopOnOtherInstanceCancelsSilentModelAndAllowsNextRequestAfterCleanup() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(streamA.chatResponse()).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
        List<ChatEventVO> events = new CopyOnWriteArrayList<>();
        subscriptions.add(serviceA.chat("silent", session).subscribe(events::add));
        await().until(() -> source.currentSubscriberCount() == 1);

        serviceB.stop(session);
        await().atMost(Duration.ofSeconds(5)).untilTrue(cancelled);
        await().untilAsserted(() -> assertThat(events).extracting(ChatEventVO::getEventType)
                .containsExactly(1002));
        assertThat(redisA.hasKey(key())).isFalse();
        assertSuccessfulRetryOnB();
    }

    @Test
    void stopRetainsOccupiedSlotUntilOwnerReleasesIt() {
        var generation = storeA.acquire(session);
        storeB.stop(session);
        assertThat(storeA.renew(generation)).isFalse();
        assertThat(storeA.isCurrent(generation)).isTrue();
        assertThatThrownBy(() -> storeB.acquire(session)).isInstanceOf(BadRequestException.class);
        storeA.release(generation);
        storeB.release(storeB.acquire(session));
    }

    @Test
    void stopOnOtherInstancePersistsPartialInSharedHistory() {
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(streamA.chatResponse()).thenReturn(source.asFlux());
        List<ChatEventVO> events = new CopyOnWriteArrayList<>();
        subscriptions.add(serviceA.chat("question", session).subscribe(events::add));
        await().until(() -> source.currentSubscriberCount() == 1);
        source.tryEmitNext(chunk("partial ")).orThrow();
        source.tryEmitNext(chunk("answer")).orThrow();
        serviceB.stop(session);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(events)
                .extracting(ChatEventVO::getEventType).containsExactly(1001, 1001, 1002));
        var messages = redisB.opsForList().range(historyKey(), 0, -1).stream()
                .map(MessageUtil::toMessage).toList();
        assertThat(messages).extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("question", "partial answer");
        assertThat(MessageUtil.isStopped(messages.get(1))).isTrue();
        assertThat(redisB.hasKey(key())).isFalse();
        assertSuccessfulRetryOnB();
        assertThat(redisB.opsForList().range(historyKey(), 0, -1).stream().map(MessageUtil::toMessage))
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("question", "partial answer", "retry", "重试成功");
    }

    @Test
    void currentOwnerCheckRejectsPreviousGenerations() {
        var old = storeA.acquire(session);
        storeB.stop(session);
        assertThat(storeA.isCurrent(old)).isTrue();
        storeA.release(old);
        var current = storeB.acquire(session);
        storeB.stop(session);
        assertThat(storeA.isCurrent(old)).isFalse();
        assertThat(storeB.isCurrent(current)).isTrue();
        storeB.release(current);
    }

    @Test
    void staleOwnerCannotRenewOrDeleteNewOwnerAfterLeaseExpires() {
        var old = storeA.acquire(session);
        assertThat(redisA.getExpire(key(), TimeUnit.MILLISECONDS)).isBetween(1L, 30_000L);
        redisA.expire(key(), Duration.ofMillis(100));
        await().until(() -> !Boolean.TRUE.equals(redisA.hasKey(key())));
        var current = storeB.acquire(session);
        Object currentId = redisB.opsForHash().get(key(), "requestId");

        assertThat(storeA.renew(old)).isFalse();
        storeA.release(old);
        assertThat(redisB.opsForHash().get(key(), "requestId")).isEqualTo(currentId);
        assertThat(storeB.renew(current)).isTrue();
        storeB.release(current);
    }

    @Test
    void oldStreamCleanupCannotRemoveNewStreamAfterLeaseExpires() throws Exception {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean pauseRenewal = new AtomicBoolean();
        doAnswer(invocation -> {
            if (pauseRenewal.get()) {
                checking.countDown();
                if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("check timed out");
            }
            return invocation.callRealMethod();
        }).when(storeA).renew(any());
        AtomicBoolean oldCancelled = new AtomicBoolean();
        Sinks.Many<ChatResponse> oldSource = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<ChatResponse> newSource = Sinks.many().unicast().onBackpressureBuffer();
        when(streamA.chatResponse()).thenReturn(oldSource.asFlux().doOnCancel(() -> oldCancelled.set(true)));
        when(streamB.chatResponse()).thenReturn(newSource.asFlux());
        List<ChatEventVO> oldEvents = new CopyOnWriteArrayList<>();
        List<ChatEventVO> newEvents = new CopyOnWriteArrayList<>();
        subscriptions.add(serviceA.chat("old", session).subscribe(oldEvents::add));
        try {
            // Pause a heartbeat of an existing model stream, not the pre-model active check.
            await().until(() -> oldSource.currentSubscriberCount() == 1);
            pauseRenewal.set(true);
            assertThat(checking.await(3, TimeUnit.SECONDS)).isTrue();
            redisA.expire(key(), Duration.ofMillis(100));
            await().until(() -> !Boolean.TRUE.equals(redisA.hasKey(key())));
            subscriptions.add(serviceB.chat("new", session).subscribe(newEvents::add));
            await().until(() -> newSource.currentSubscriberCount() == 1);
            Object newId = redisB.opsForHash().get(key(), "requestId");
            resume.countDown();

            await().untilTrue(oldCancelled);
            await().untilAsserted(() -> assertThat(oldEvents).extracting(ChatEventVO::getEventType)
                    .containsExactly(1002));
            assertThat(redisB.opsForHash().get(key(), "requestId")).isEqualTo(newId);
            assertBusy(serviceA);
            newSource.tryEmitNext(chunk("新请求继续")).orThrow();
            await().untilAsserted(() -> assertThat(newEvents).extracting(ChatEventVO::getEventData)
                    .containsExactly("新请求继续"));
        } finally {
            resume.countDown();
        }
    }

    @Test
    void renewalExtendsLeaseEvenWhenModelIsSilent() {
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(streamA.chatResponse()).thenReturn(source.asFlux());
        subscriptions.add(serviceA.chat("silent", session).subscribe());
        await().until(() -> source.currentSubscriberCount() == 1);
        redisA.expire(key(), Duration.ofSeconds(3));

        await().atMost(Duration.ofSeconds(4)).until(() -> redisA.getExpire(key(), TimeUnit.SECONDS) > 20);
        assertBusy(serviceB);
    }

    @Test
    void completionReleasesSlotBeforeStopEvent() {
        when(streamA.chatResponse()).thenReturn(Flux.just(chunk("完成")));
        assertThat(serviceA.chat("first", session).collectList().block(Duration.ofSeconds(5)))
                .extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
        assertThat(redisA.hasKey(key())).isFalse();
        assertSuccessfulRetryOnB();
    }

    @Test
    void modelErrorReleasesSlot() {
        when(streamA.chatResponse()).thenReturn(Flux.error(new IllegalStateException("provider failed")));
        assertGenerationFailed(serviceA.chat("first", session).collectList().block(Duration.ofSeconds(5)));
        assertThat(redisA.hasKey(key())).isFalse();
        assertSuccessfulRetryOnB();
    }

    @Test
    void setupErrorReleasesSlot() {
        when(streamA.chatResponse()).thenThrow(new IllegalStateException("setup failed"));
        assertGenerationFailed(serviceA.chat("first", session).collectList().block(Duration.ofSeconds(5)));
        assertThat(redisA.hasKey(key())).isFalse();
        assertSuccessfulRetryOnB();
    }

    @Test
    void clientCancellationReleasesSlot() {
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(streamA.chatResponse()).thenReturn(source.asFlux());
        Disposable request = serviceA.chat("first", session).subscribe();
        subscriptions.add(request);
        await().until(() -> source.currentSubscriberCount() == 1);
        request.dispose();
        await().until(() -> !Boolean.TRUE.equals(redisA.hasKey(key())));
        assertSuccessfulRetryOnB();
    }

    @Test
    void clientCancellationPersistsPartialInSharedHistoryBeforeReleasingSlot() {
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(streamA.chatResponse()).thenReturn(source.asFlux());
        CountDownLatch received = new CountDownLatch(1);
        Disposable request = serviceA.chat("question", session).subscribe(event -> received.countDown());
        subscriptions.add(request);
        await().until(() -> source.currentSubscriberCount() == 1);
        source.tryEmitNext(chunk("partial answer")).orThrow();
        await().until(() -> received.getCount() == 0);
        request.dispose();
        await().until(() -> !Boolean.TRUE.equals(redisB.hasKey(key())));
        var messages = redisB.opsForList().range(historyKey(), 0, -1).stream()
                .map(MessageUtil::toMessage).toList();
        assertThat(messages).extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("question", "partial answer");
        assertThat(MessageUtil.isStopped(messages.get(1))).isTrue();
        assertSuccessfulRetryOnB();
        assertThat(redisB.opsForList().range(historyKey(), 0, -1).stream().map(MessageUtil::toMessage))
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("question", "partial answer", "retry", "重试成功");
    }

    @Test
    void redisAcquireFailurePreventsModelCall() {
        doThrow(new RedisConnectionFailureException("unavailable")).when(storeA).acquire(session);
        assertGenerationFailed(serviceA.chat("first", session).collectList().block(Duration.ofSeconds(5)));
        verifyNoInteractions(streamA);
    }

    @Test
    void redisRenewalFailureCancelsModelAndReleasesSlot() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(streamA.chatResponse()).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
        var events = new CopyOnWriteArrayList<ChatEventVO>();
        subscriptions.add(serviceA.chat("first", session).subscribe(events::add));
        await().until(() -> source.currentSubscriberCount() == 1);
        doThrow(new RedisConnectionFailureException("unavailable")).when(storeA).renew(any());
        await().atMost(Duration.ofSeconds(5)).untilTrue(cancelled);
        await().untilAsserted(() -> assertGenerationFailed(events));
        assertThat(redisA.hasKey(key())).isFalse();
    }

    @Test
    void releaseFailureLeavesExpiringSlotInsteadOfDeletingAnotherOwner() {
        doThrow(new RedisConnectionFailureException("release unavailable")).when(storeA).release(any());
        when(streamA.chatResponse()).thenReturn(Flux.just(chunk("完成")));
        assertThat(serviceA.chat("first", session).collectList().block(Duration.ofSeconds(5)))
                .extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
        assertThat(redisA.getExpire(key(), TimeUnit.MILLISECONDS)).isBetween(1L, 30_000L);
        assertBusy(serviceB);
        redisA.expire(key(), Duration.ofMillis(100));
        await().until(() -> !Boolean.TRUE.equals(redisA.hasKey(key())));
        assertSuccessfulRetryOnB();
    }

    @Test
    void concurrentInstancesAllowExactlyOneOwner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                ChatGenerationStore store = i % 2 == 0 ? storeA : storeB;
                futures.add(executor.submit(() -> {
                    assertThat(start.await(3, TimeUnit.SECONDS)).isTrue();
                    try {
                        store.acquire(session);
                        return true;
                    } catch (BadRequestException busy) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int admitted = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void assertBusy(ChatServiceImpl service) {
        assertGenerationFailed(service.chat("duplicate", session).collectList().block(Duration.ofSeconds(5)));
    }

    private void assertGenerationFailed(List<ChatEventVO> events) {
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("CHAT_GENERATION_FAILED");
    }

    private void assertSuccessfulRetryOnB() {
        when(streamB.chatResponse()).thenReturn(Flux.just(chunk("重试成功")));
        assertThat(serviceB.chat("retry", session).collectList().block(Duration.ofSeconds(5)))
                .extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
    }

    private String key() {
        return "aigc:chat:generating:" + session;
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String historyKey() {
        return "codex:aigc:history:null_" + session;
    }

    private static ChatServiceImpl service(ChatGenerationStore store, ChatClient.StreamResponseSpec stream,
                                          StringRedisTemplate redis) {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> stream.chatResponse());
        SpringAIConfig ai = new SpringAIConfig();
        RedisChatMemoryRepository repository = new RedisChatMemoryRepository("codex:aigc:history:");
        ReflectionTestUtils.setField(repository, "stringRedisTemplate", redis);
        var memory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).build();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(ai.messageChatMemoryAdvisor(memory))
                .build();
        SystemPromptConfig config = mock(SystemPromptConfig.class);
        when(config.getChatSystemMessage()).thenReturn(new AtomicReference<>("你是学习助手"));
        return com.tianji.aigc.ChatTestSupport.ordinaryChat(client, config, store, memory);
    }
}
