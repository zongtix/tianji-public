package com.tianji.aigc.service;

import com.tianji.aigc.agent.*;
import com.tianji.aigc.config.RagObservabilityConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.longterm.UserMemoryContext;
import com.tianji.aigc.longterm.UserMemoryService;
import com.tianji.aigc.longterm.UserMemoryStore;
import com.tianji.aigc.longterm.UserMemoryExtractor;
import com.tianji.aigc.longterm.UserMemoryWorker;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.aigc.vo.ChatEventVO;
import io.micrometer.observation.*;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatObservabilityTest {
    private final List<SpanData> spans = new CopyOnWriteArrayList<>();
    private final ObservationRegistry registry = ObservationRegistry.create();
    private ObservationRegistry previous;
    private SdkTracerProvider provider;
    private final AtomicBoolean titleSubscribed = new AtomicBoolean();
    private final AtomicBoolean hangTitle = new AtomicBoolean();
    private final AtomicBoolean failRoute = new AtomicBoolean();
    private final AtomicBoolean hangAnswer = new AtomicBoolean();
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        SpanExporter exporter = new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> values) { spans.addAll(values); return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(
                        new OtelTracer(provider.get("chat-test"), new OtelCurrentTraceContext(), event -> {})))
                .observationFilter(new RagObservabilityConfig().ragTraceAttributes());
        previous = ObservationThreadLocalAccessor.getInstance().getObservationRegistry();
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(registry);
        Hooks.enableAutomaticContextPropagation();

        var prompts = mock(SystemPromptConfig.class);
        when(prompts.getChatSystemMessage()).thenReturn(new AtomicReference<>("system {now}"));
        when(prompts.agentMessage(any())).thenReturn("role");
        var memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(List.of());
        var route = new RouteAgent(model("route", "{\"agent\":\"KNOWLEDGE\",\"clarification\":\"\"}"), prompts);
        var answer = new KnowledgeAgent(ChatClient.builder(model("answer", "回答"), registry, null, null).build(), prompts);
        List<Agent> agents = new ArrayList<>(List.of(route, answer));
        for (var type : List.of(AgentTypeEnum.RECOMMEND, AgentTypeEnum.CONSULT)) {
            var unused = mock(Agent.class);
            when(unused.getAgentType()).thenReturn(type);
            agents.add(unused);
        }
        var sessions = mock(ChatSessionService.class);
        when(sessions.requireOwnedSession(anyString())).thenAnswer(call -> session(call.getArgument(0)));
        when(sessions.requireOwnedSession(anyString(), eq(42L))).thenAnswer(call -> session(call.getArgument(0)));
        when(sessions.claimFirstTurnTitle(anyString(), eq(42L))).thenReturn(true);
        var store = mock(ChatGenerationStore.class);
        var lease = mock(ChatGenerationStore.Generation.class);
        when(lease.isValid()).thenReturn(true);
        when(store.acquire(anyString())).thenReturn(lease);
        when(store.renew(lease)).thenReturn(true);
        when(store.isCurrent(lease)).thenReturn(true);
        service = new ChatServiceImpl(new AgentDispatcher(agents, memory), store, memory,
                new ChatTitleGenerator(model("title", "{\"title\":\"学习问答\"}")), sessions, new KnowledgeBaseAccess(),
                new DefaultListableBeanFactory().getBeanProvider(KnowledgeRetrievalAdvisor.class), registry);
    }

    @AfterEach
    void cleanUp() {
        Hooks.disableAutomaticContextPropagation();
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(previous);
        provider.close();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TJ_TRACE_SYNTHETIC_LIVE", matches = "true")
    void exportSyntheticConversationToIsolatedLangfuseProject() {
        String endpoint = System.getenv("TJ_TRACE_SYNTHETIC_ENDPOINT");
        assertThat(endpoint).isEqualTo("http://127.0.0.1:3000/api/public/otel/v1/traces");
        String publicKey = System.getenv("TJ_TRACE_SYNTHETIC_PUBLIC_KEY");
        String secretKey = System.getenv("TJ_TRACE_SYNTHETIC_SECRET_KEY");
        assertThat(publicKey).isNotBlank();
        assertThat(secretKey).isNotBlank();
        var memories = mock(UserMemoryService.class);
        var item = new UserMemoryStore.Memory(1, "background", "合成背景：学过 Java", 1, 1, "old", "request", LocalDateTime.now());
        when(memories.prepare(anyLong(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(call -> new UserMemoryContext(42, call.getArgument(2), false, List.of(item)));
        ReflectionTestUtils.setField(service, "userMemory", memories);
        try (var exporter = new RagObservabilityConfig().langfuseSpanExporter(endpoint, publicKey, secretKey)) {
            service.chat("合成问题：默认不记录", "langfuse-synthetic-off").collectList().block(Duration.ofSeconds(5));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(1));
            assertThat(spans.toString()).doesNotContain("合成问题：默认不记录", "合成背景：学过 Java");
            assertThat(exporter.export(spans).join(10, java.util.concurrent.TimeUnit.SECONDS).isSuccess()).isTrue();
            spans.clear();
            registry.observationConfig().observationFilter(new RagObservabilityConfig().ragTraceAttributes(false, true));
            ReflectionTestUtils.setField(service, "captureConversation", true);
            ReflectionTestUtils.setField(service, "captureMemoryDetails", true);
            service.chat("合成问题：继续学习 Java", "langfuse-synthetic-full").collectList().block(Duration.ofSeconds(5));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(1));
            var store = mock(UserMemoryStore.class);
            var extractor = mock(UserMemoryExtractor.class);
            var batch = new UserMemoryStore.Batch(42, "langfuse-synthetic-full", 0, "token", List.of(), List.of(), 1);
            when(store.claimDue(eq(3), any(Duration.class))).thenReturn(batch);
            var candidate = new UserMemoryStore.Candidate("goal", "合成目标：学习后端", "synthetic", "学习后端");
            when(extractor.extract(batch)).thenReturn(List.of(candidate));
            when(store.apply(eq(batch), any())).thenReturn(true);
            try (var worker = new UserMemoryWorker(store, extractor, registry)) {
                ReflectionTestUtils.setField(worker, "captureMemoryDetails", true);
                worker.poll();
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                        assertThat(spans).extracting(SpanData::getName).contains("user_memory.extract"));
            }
            assertThat(exporter.export(spans).join(10, java.util.concurrent.TimeUnit.SECONDS).isSuccess()).isTrue();
        }
    }

    @Test
    void syntheticConversationAttributesReachAnIsolatedOtlpReceiver() throws Exception {
        var received = new AtomicReference<byte[]>();
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ReflectionTestUtils.setField(service, "captureConversation", true);
            service.chat("合成测试：我想学习 Java", "synthetic-otlp").collectList().block(Duration.ofSeconds(5));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(1));
            try (var exporter = new RagObservabilityConfig().langfuseSpanExporter(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces", "synthetic-public", "synthetic-secret")) {
                assertThat(exporter.export(spans).join(5, java.util.concurrent.TimeUnit.SECONDS).isSuccess()).isTrue();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received.get()).isNotNull());
                String wire = new String(received.get(), StandardCharsets.UTF_8);
                assertThat(wire).contains("chat.request", "langfuse.observation.input", "langfuse.observation.output",
                        "合成测试：我想学习 Java", "回答");
                assertThat(wire).doesNotContain("synthetic-secret");
            }
        } finally { server.stop(0); }
    }

    @Test
    void memoryCategoriesAreVisibleButMemoryTextRequiresItsOwnSwitch() {
        var memories = mock(UserMemoryService.class);
        var item = new UserMemoryStore.Memory(1, "background", "学过 Java", 1, 1, "old", "request", LocalDateTime.now());
        when(memories.prepare(anyLong(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(call -> new UserMemoryContext(42, call.getArgument(2), false, List.of(item)));
        ReflectionTestUtils.setField(service, "userMemory", memories);
        service.chat("继续", "hidden-memory").collectList().block(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(1));
        var keys = AttributeKey.stringKey("user_memory.keys");
        var detail = AttributeKey.stringKey("user_memory.read");
        assertThat(roots().get(0).getAttributes().get(keys)).isEqualTo("background");
        assertThat(roots().get(0).getAttributes().get(detail)).isNull();
        ReflectionTestUtils.setField(service, "captureMemoryDetails", true);
        service.chat("继续", "visible-memory").collectList().block(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(2));
        assertThat(roots().get(1).getAttributes().get(detail)).contains("学过 Java");
    }

    @Test
    void conversationCaptureIsOptInAndHidesSensitiveMessages() {
        service.chat("我想学习 Java", "default").collectList().block(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(1));
        var input = AttributeKey.stringKey("langfuse.observation.input");
        var output = AttributeKey.stringKey("langfuse.observation.output");
        assertThat(roots().get(0).getAttributes().get(input)).isNull();
        assertThat(roots().get(0).getAttributes().get(output)).isNull();

        ReflectionTestUtils.setField(service, "captureConversation", true);
        service.chat("我想学习 Java", "enabled").collectList().block(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(2));
        assertThat(roots().get(1).getAttributes().get(input)).isEqualTo("我想学习 Java");
        assertThat(roots().get(1).getAttributes().get(output)).isEqualTo("回答");

        service.chat("联系号码 138 0000 0000", "sensitive").collectList().block(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(3));
        assertThat(roots().get(2).getAttributes().get(input)).doesNotContain("138", "0000");
    }

    @Test
    void routeAnswerAndTitleShareSessionAndTraceWithoutLeakingToNextRequest() {
        for (String sessionId : List.of("first", "second")) {
            assertThat(service.chat("问题", sessionId).collectList().block(Duration.ofSeconds(5)))
                    .extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(2));
        assertThat(roots()).extracting(SpanData::getTraceId).doesNotHaveDuplicates();
        for (var root : roots()) {
            String sessionId = root.getAttributes().get(AttributeKey.stringKey("langfuse.session.id"));
            assertThat(sessionId).isIn("42_first", "42_second");
            var children = spans.stream().filter(s -> s.getTraceId().equals(root.getTraceId())).toList();
            assertThat(children).extracting(SpanData::getName).contains("route", "answer", "title");
            assertThat(children).allSatisfy(s -> assertThat(s.getAttributes()
                    .get(AttributeKey.stringKey("langfuse.session.id"))).isEqualTo(sessionId));
            assertThat(root.getAttributes().get(AttributeKey.stringKey("chat.final.status"))).isEqualTo("ON_COMPLETE");
            assertThat(time(root, "chat.first_token.ms")).isLessThanOrEqualTo(time(root, "chat.last_token.ms"));
            assertThat(time(root, "chat.last_token.ms")).isLessThanOrEqualTo(time(root, "chat.answer_complete.ms"));
            assertThat(time(root, "chat.answer_complete.ms")).isLessThanOrEqualTo(time(root, "chat.total.ms"));
        }
        Observation.start("unrelated", registry).stop();
        assertThat(spans.get(spans.size() - 1).getAttributes().get(AttributeKey.stringKey("langfuse.session.id"))).isNull();
    }

    @Test
    void cancellationDuringTitleEndsConversationAndModelSpans() {
        hangTitle.set(true);
        var subscription = service.chat("问题", "cancelled").subscribe();
        await().atMost(Duration.ofSeconds(3)).untilTrue(titleSubscribed);
        subscription.dispose();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(roots()).hasSize(1);
            assertThat(spans).extracting(SpanData::getName).contains("title");
        });
        assertThat(roots().get(0).getAttributes().get(AttributeKey.stringKey("chat.final.status"))).isEqualTo("CANCEL");
        assertThat(time(roots().get(0), "chat.answer_complete.ms")).isLessThanOrEqualTo(time(roots().get(0), "chat.total.ms"));
    }

    @Test
    void cancelledAnswerKeepsFirstTokenTimingWithoutInventingCompletion() {
        hangAnswer.set(true);
        var received = new AtomicBoolean();
        var subscription = service.chat("问题", "cancel-answer").doOnNext(event -> received.set(true)).subscribe();
        await().atMost(Duration.ofSeconds(3)).untilTrue(received);
        subscription.dispose();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(1));
        assertThat(time(roots().get(0), "chat.first_token.ms")).isGreaterThanOrEqualTo(0);
        assertThat(roots().get(0).getAttributes().get(AttributeKey.stringKey("chat.answer_complete.ms"))).isNull();
    }

    @Test
    void handledRoutingFailureMarksRootAsErrorAndStillEndsNormally() {
        failRoute.set(true);
        assertThat(service.chat("问题", "failed").collectList().block(Duration.ofSeconds(5)))
                .extracting(ChatEventVO::getEventType).containsExactly(1005, 1002);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(roots()).hasSize(1));
        assertThat(roots().get(0).getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(spans).extracting(SpanData::getName).doesNotContain("answer", "title");
        assertThat(roots().get(0).getAttributes().get(AttributeKey.stringKey("chat.first_token.ms"))).isNull();
    }

    private List<SpanData> roots() { return spans.stream().filter(s -> s.getName().equals("chat.request")).toList(); }

    private long time(SpanData span, String key) { return Long.parseLong(span.getAttributes().get(AttributeKey.stringKey(key))); }

    private ChatSession session(String id) { return ChatSession.builder().sessionId(id).userId(42L).build(); }

    // 替换外部模型，保留模型观测与真实路由、ChatClient、标题和跨线程调用链。
    private ChatModel model(String name, String text) {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            return Flux.defer(() -> {
                var context = ChatModelObservationContext.builder().prompt(prompt).provider("test").build();
                var observation = Observation.createNotStarted("gen_ai.client.operation", () -> context, registry)
                        .contextualName(name).start();
                var response = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
                Flux<ChatResponse> result = Flux.just(response).doOnNext(context::setResponse);
                if (name.equals("route") && failRoute.get()) result = Flux.error(new IllegalStateException("route unavailable"));
                if (name.equals("title") && hangTitle.get()) result = Flux.never();
                if (name.equals("answer") && hangAnswer.get()) result = result.concatWith(Flux.never());
                return result.doOnSubscribe(s -> { if (name.equals("title")) titleSubscribed.set(true); })
                        .doFinally(signal -> observation.stop())
                        .contextWrite(c -> c.put(ObservationThreadLocalAccessor.KEY, observation));
            }).subscribeOn(Schedulers.boundedElastic());
        });
        return model;
    }
}
