package com.tianji.aigc.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianji.aigc.config.McpProperties;
import com.tianji.aigc.tools.CourseRecommendations;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.aigc.tools.result.CourseCard;
import com.tianji.api.mcp.CourseDetailContract;
import io.micrometer.observation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import com.tianji.aigc.agent.*;
import com.tianji.aigc.config.*;
import com.tianji.aigc.tools.CourseToolPreparationAdvisor;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseMcpClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpProperties settings = new McpProperties();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final List<Observation.Context> spans = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;
    private CourseMcpClient client;
    private volatile boolean delay;
    private volatile String delayMethod = "tools/call";
    private volatile boolean extraTool;
    private volatile boolean badSchema;
    private volatile boolean badServer;
    private volatile int status = 200;
    private volatile Map<String, Object> result;

    @BeforeEach
    void setup() throws Exception {
        settings.setRequestTimeout(Duration.ofMillis(700));
        settings.setRoundBudget(Duration.ofSeconds(3));
        settings.setToken("test-token-never-in-meta-or-spans");
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            public boolean supportsContext(Observation.Context context) { return true; }
            public void onStop(Observation.Context context) { spans.add(context); }
        });
        result = content(json.writeValueAsString(CourseDetailContract.Result.found(detail())), false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/mcp", exchange -> {
            try {
                if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); return; }
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + settings.getToken());
                @SuppressWarnings("unchecked") var request = (Map<String, Object>) json.readValue(exchange.getRequestBody(), Map.class);
                requests.add(request);
                String method = request.get("method").toString();
                if (method.equals("tools/call")) calls.incrementAndGet();
                if (delay && method.equals(delayMethod)) { entered.countDown(); release.await(4, TimeUnit.SECONDS); }
                if (!request.containsKey("id")) { exchange.sendResponseHeaders(202, -1); return; }
                Object response = switch (method) {
                    case "initialize" -> Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", badServer ? "wrong-server" : "course-catalog", "version", "1.0.0"));
                    case "tools/list" -> {
                        var schema = json.readValue(CourseDetailContract.INPUT_SCHEMA, Map.class);
                        if (badSchema) schema.put("additionalProperties", true);
                        var tool = Map.of("name", CourseDetailContract.TOOL_NAME, "inputSchema", schema);
                        yield Map.of("tools", extraTool ? List.of(tool, Map.of("name", "unknown", "inputSchema", schema)) : List.of(tool));
                    }
                    case "tools/call" -> result;
                    default -> Map.of();
                };
                byte[] bytes = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", response));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        settings.setCourseServerUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        client = new CourseMcpClient(settings, json, observations);
    }

    @AfterEach
    void cleanup() {
        release.countDown(); client.close(); server.stop(0); executor.shutdownNow();
    }

    @Test
    void actualProtocolPreservesLargeIdsUnitsAndNullsWithoutSendingLocalState() {
        var parent = Observation.start("chat.request", observations);
        try (var ignored = parent.openScope()) {
            var dto = client.read(detail().getId(), scope());
            assertThat(dto).usingRecursiveComparison().isEqualTo(detail());
            client.read(detail().getId(), scope());
        } finally { parent.stop(); }
        assertThat(requests.stream().filter(r -> "initialize".equals(r.get("method"))).count()).isEqualTo(1);
        assertThat(requests.stream().filter(r -> "tools/list".equals(r.get("method"))).count()).isEqualTo(1);
        assertThat(calls).hasValue(2);
        assertThat(requests.stream().filter(r -> "tools/call".equals(r.get("method"))))
                .allSatisfy(r -> assertThat(r.get("params").toString()).doesNotContain("_meta", "token", "courseRecommendations", "userId"));
        // Cold connection: initialize/discover/call; warm connection: call only.
        assertThat(spans.stream().filter(s -> s.getName().startsWith("mcp."))).hasSize(4)
                .allSatisfy(s -> {
                    assertThat(s.getParentObservation()).isSameAs(parent);
                    assertThat(s.getAllKeyValues().toString()).doesNotContain(settings.getToken(), detail().getName());
                });
    }

    @Test
    void onlyExplicitNotFoundBecomesNullAndDoesNotFallback() {
        result = content("{\"schemaVersion\":1,\"status\":\"NOT_FOUND\",\"data\":null}", false);
        assertThat(client.read(1L, scope())).isNull();
        result = content("{\"code\":\"FORBIDDEN\"}", true);
        assertThatThrownBy(() -> client.read(1L, scope())).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.kind()).isEqualTo(McpToolException.Kind.FORBIDDEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "{\"schemaVersion\":2,\"status\":\"NOT_FOUND\"}",
            "{\"schemaVersion\":1,\"status\":\"FOUND\",\"data\":{\"id\":\"2\"}}",
            "{\"schemaVersion\":1,\"status\":\"FOUND\",\"data\":{\"id\":1}}",
            "{\"schemaVersion\":1,\"status\":\"NOT_FOUND\",\"extra\":\"secret\"}"})
    void malformedContractsAreFailures(String text) {
        result = content(text, false);
        assertThatThrownBy(() -> client.read(1L, scope())).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.kind()).isEqualTo(McpToolException.Kind.PROTOCOL));
    }

    @Test
    void rejectsOversizedContentAndCatalogue() throws Exception {
        result = content("x".repeat(CourseDetailContract.MAX_BYTES + 1), false);
        assertThatThrownBy(() -> client.read(1L, scope())).isInstanceOf(McpToolException.class);
        var dto = detail(); dto.setId(1L); dto.setCatalogue(Collections.nCopies(2001, "chapter"));
        result = content(json.writeValueAsString(CourseDetailContract.Result.found(dto)), false);
        assertThatThrownBy(() -> client.read(1L, scope())).isInstanceOf(McpToolException.class);
        result = Map.of("content", List.of(Map.of("type", "image", "data", "", "mimeType", "image/png")), "isError", false);
        assertThatThrownBy(() -> client.read(1L, scope())).isInstanceOf(McpToolException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "schema"})
    void rejectsDiscoveryBeforeCalling(String mode) {
        extraTool = mode.equals("unknown"); badSchema = mode.equals("schema");
        assertThatThrownBy(() -> client.read(1L, scope())).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.kind()).isEqualTo(McpToolException.Kind.PROTOCOL));
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"server", "schema"})
    void nextRequestRecoversAfterInvalidSuccessfulDiscovery(String mode) {
        badServer = mode.equals("server"); badSchema = mode.equals("schema");
        assertThatThrownBy(() -> client.read(1L, scope())).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.kind()).isEqualTo(McpToolException.Kind.PROTOCOL));
        assertThat(calls).hasValue(0);
        badServer = false; badSchema = false;
        var fixture = detail(); fixture.setId(1L);
        result = content(writeResult(fixture), false);
        assertThat(client.read(1L, scope()).getId()).isEqualTo(1L);
        assertThat(calls).hasValue(1);
    }

    private String writeResult(CourseBaseInfoDTO dto) {
        try { return json.writeValueAsString(CourseDetailContract.Result.found(dto)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"initialize", "tools/list", "tools/call"})
    void eachStageHasBoundedTimeout(String method) {
        delay = true; delayMethod = method;
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.read(1L, scope())).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.kind()).isEqualTo(McpToolException.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aStoppedCallerDropsResultAndDoesNotCloseOtherCallersConnection() throws Exception {
        var active = new AtomicBoolean(true);
        delay = true;
        var pending = executor.submit(() -> client.read(detail().getId(), new McpCallScope(active::get, () -> true)));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        active.set(false);
        assertThatThrownBy(() -> pending.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
        release.countDown(); delay = false;
        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
        assertThat(requests.stream().filter(r -> "initialize".equals(r.get("method"))).count()).isEqualTo(1);
    }

    @Test
    void twoParallelRequestsInitializeOnceAndKeepSeparateResults() throws Exception {
        var first = executor.submit(() -> client.read(detail().getId(), scope()));
        var second = executor.submit(() -> client.read(detail().getId(), scope()));
        assertThat(first.get(2, TimeUnit.SECONDS)).isNotSameAs(second.get(2, TimeUnit.SECONDS));
        assertThat(calls).hasValue(2);
        assertThat(requests.stream().filter(r -> "initialize".equals(r.get("method"))).count()).isEqualTo(1);
        assertThat(requests.stream().filter(r -> "tools/list".equals(r.get("method"))).count()).isEqualTo(1);
    }

    @Test
    void roundBudgetIsSharedAndStoppedScopeDoesNotStartNetwork() throws Exception {
        settings.setRoundBudget(Duration.ofMillis(300));
        var scope = scope();
        client.read(detail().getId(), scope);
        Thread.sleep(320);
        assertThatThrownBy(() -> client.read(detail().getId(), scope)).isInstanceOf(McpToolException.class);
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> client.read(1L, new McpCallScope(() -> false, () -> true))).isInstanceOf(CancellationException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void noLateCardsAfterLocalAvailabilityCheckLosesOwnership() {
        var local = mock(CourseClient.class);
        var active = new AtomicBoolean(true);
        var scope = new McpCallScope(active::get, () -> true);
        when(local.getSimpleInfoList(any())).thenAnswer(invocation -> { active.set(false); return List.of(); });
        var recommendations = new CourseRecommendations();
        recommendations.addCandidates(List.of(new CourseCard(detail().getId().toString(), "fixture", null, 14900, 12, null)));
        var context = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, recommendations, McpCallScope.CONTEXT_KEY, scope));
        var tools = new CourseTools(local, mock(SearchClient.class), null, client);
        assertThatThrownBy(() -> tools.queryCourseById(detail().getId(), context)).isInstanceOf(CancellationException.class);
        assertThat(recommendations.snapshot()).isEmpty();
        assertThat(recommendations.canRecommend(detail().getId().toString())).isFalse();
        verify(local, never()).getCourseBaseInfoById(any());
    }

    @Test
    void overloadIsRejectedWithoutQueueingAnotherRequest() throws Exception {
        client.close(); settings.setMaxConcurrentCalls(1);
        client = new CourseMcpClient(settings, json, observations);
        delay = true;
        var pending = executor.submit(() -> client.read(detail().getId(), scope()));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> client.read(detail().getId(), scope())).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.kind()).isEqualTo(McpToolException.Kind.BUSY));
        assertThat(calls).hasValue(1);
        release.countDown(); pending.get(2, TimeUnit.SECONDS);
    }

    @Test
    void stoppingOneInitializationWaiterDoesNotCancelSharedInitialization() throws Exception {
        delay = true; delayMethod = "initialize";
        var active = new AtomicBoolean(true);
        var first = executor.submit(() -> client.read(detail().getId(), new McpCallScope(active::get, () -> true)));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        var second = executor.submit(() -> client.read(detail().getId(), scope()));
        active.set(false);
        assertThatThrownBy(() -> first.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
        release.countDown();
        assertThat(second.get(2, TimeUnit.SECONDS).getId()).isEqualTo(detail().getId());
        assertThat(requests.stream().filter(r -> "initialize".equals(r.get("method"))).count()).isEqualTo(1);
    }

    @Test
    void independentNextRequestCanRecoverAfterFailedInitializationWithoutRetryingFirstCall() {
        status = 500;
        assertThatThrownBy(() -> client.read(detail().getId(), scope())).isInstanceOf(McpToolException.class);
        assertThat(calls).hasValue(0);
        status = 200;
        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingConsultAndRecommendAgentsUseMcpWithoutChangingModelTools(boolean recommend) {
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var detailCall = toolCall("queryCourseById", "{\"courseId\":" + detail().getId() + "}");
        if (recommend) {
            when(model.call(any(Prompt.class))).thenReturn(toolCall("searchCourses", "{\"keyword\":\"fixture\"}"),
                    detailCall, toolCall("recommendCourses", "{\"selections\":[{\"courseId\":\"" + detail().getId()
                            + "\",\"reason\":\"fixture reason\"}]}"));
        } else {
            when(model.call(any(Prompt.class))).thenReturn(detailCall,
                    new ChatResponse(List.of(new Generation(new AssistantMessage("hidden preparation")))));
        }
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()).isEmpty();
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("final answer")))));
        });
        var chat = ChatClient.builder(model).defaultAdvisors(new CourseToolPreparationAdvisor(model)).build();
        var prompts = new SystemPromptConfig(mock(com.alibaba.cloud.nacos.NacosConfigManager.class), new AIProperties());
        prompts.init();
        var local = mock(CourseClient.class);
        var search = mock(SearchClient.class);
        var simple = new CourseSimpleInfoDTO(); simple.setId(detail().getId()); simple.setStatus(2);
        simple.setName(detail().getName()); simple.setPrice(14900); simple.setValidDuration(12);
        when(local.getSimpleInfoList(List.of(detail().getId()))).thenReturn(List.of(simple));
        when(search.queryCoursesIdByName("fixture")).thenReturn(List.of(detail().getId()));
        var tools = new CourseTools(local, search, null, client);
        Agent agent = recommend ? new RecommendAgent(chat, prompts, tools) : new ConsultAgent(chat, prompts, tools);
        var recommendations = new CourseRecommendations();
        var answer = agent.processStream(new AgentContext("请查课程详情", "mcp-test", recommendations,
                () -> true, () -> true, null, null, List.of(), null)).collectList().block(Duration.ofSeconds(4));
        assertThat(answer).extracting(response -> response.getResult().getOutput().getText()).containsExactly("final answer", "");
        assertThat(answer.get(1).getResult().getOutput().getMetadata()).containsKey("courseCards");
        assertThat(calls).hasValue(recommend ? 2 : 1);
        assertThat(recommendations.snapshot()).singleElement().satisfies(card -> {
            assertThat(card.getCourseId()).isEqualTo(detail().getId().toString());
            assertThat(card.getPriceInCents()).isEqualTo(14900);
        });
        verify(local, never()).getCourseBaseInfoById(any());
    }

    private ChatResponse toolCall(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("hidden")
                .toolCalls(List.of(new AssistantMessage.ToolCall(name + "-call", "function", name, arguments))).build())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deterministicDepthAnswerUsesEitherSourceWithoutFinalModelRewrite(boolean remote) throws Exception {
        var dto = detail();
        dto.setUsePeople("有Java基础，熟练使用SpringBoot、MyBatis");
        dto.setCatalogue(List.of("JavaScript / 变量", "JavaScript / 控制语句", "Vue / 入门"));
        result = content(json.writeValueAsString(CourseDetailContract.Result.found(dto)), false);
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(toolCall("queryCourseById", "{\"courseId\":" + dto.getId() + "}"),
                new ChatResponse(List.of(new Generation(new AssistantMessage("根据目录，可以判断覆盖范围有限。")))));
        var chat = ChatClient.builder(model).defaultAdvisors(new CourseToolPreparationAdvisor(model)).build();
        var prompts = new SystemPromptConfig(mock(com.alibaba.cloud.nacos.NacosConfigManager.class), new AIProperties());
        prompts.init();
        var local = mock(CourseClient.class);
        when(local.getCourseBaseInfoById(dto.getId())).thenReturn(dto);
        var simple = new CourseSimpleInfoDTO(); simple.setId(dto.getId()); simple.setName(dto.getName());
        simple.setStatus(2); simple.setPrice(14900);
        when(local.getSimpleInfoList(List.of(dto.getId()))).thenReturn(List.of(simple));
        var tools = remote ? new CourseTools(local, mock(SearchClient.class), null, client)
                : new CourseTools(local, mock(SearchClient.class));
        var answer = new ConsultAgent(chat, prompts, tools).processStream(new AgentContext(
                dto.getName() + "中JavaScript讲多深？是否完整覆盖？", "fact-source-test", new CourseRecommendations(),
                () -> true, () -> true, null, null, List.of(), null)).collectList().block(Duration.ofSeconds(4));

        assertThat(answer.get(0).getResult().getOutput().getText()).contains("JavaScript / 变量", "无法确认讲解深度", "无法确认是否完整覆盖")
                .doesNotContain("覆盖范围有限", "Vue", "149");
        assertThat(answer.get(1).getResult().getOutput().getMetadata()).containsKey("courseCards");
        verify(model, never()).stream(any(Prompt.class));
        verify(local, remote ? never() : times(1)).getCourseBaseInfoById(dto.getId());
        assertThat(calls).hasValue(remote ? 1 : 0);
    }

    @Test
    void remoteFailurePropagatesThroughToolManagerWithoutFinalAnswerOrCards() {
        result = content("{\"code\":\"FORBIDDEN\"}", true);
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(toolCall("queryCourseById", "{\"courseId\":1}"));
        var chat = ChatClient.builder(model).defaultAdvisors(new CourseToolPreparationAdvisor(model)).build();
        var prompts = new SystemPromptConfig(mock(com.alibaba.cloud.nacos.NacosConfigManager.class), new AIProperties());
        prompts.init();
        var local = mock(CourseClient.class);
        var tools = new CourseTools(local, mock(SearchClient.class), null, client);
        var recommendations = new CourseRecommendations();
        assertThatThrownBy(() -> new ConsultAgent(chat, prompts, tools).processStream(new AgentContext(
                "查课程", "mcp-error", recommendations, () -> true, () -> true, null, null, List.of(), null))
                .blockLast(Duration.ofSeconds(3))).satisfies(error -> {
                    assertThat(McpToolException.find(error)).isNotNull();
                    assertThat(McpToolException.find(error).kind()).isEqualTo(McpToolException.Kind.FORBIDDEN);
                });
        verify(model, never()).stream(any(Prompt.class));
        verifyNoInteractions(local);
        assertThat(recommendations.snapshot()).isEmpty();
    }

    private McpCallScope scope() { return new McpCallScope(() -> true, () -> true); }
    private static Map<String, Object> content(String text, boolean error) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error);
    }
    private static CourseBaseInfoDTO detail() {
        var dto = new CourseBaseInfoDTO(); dto.setId(1589888774267072513L); dto.setName("Protocol fixture only");
        dto.setPrice(14900); dto.setFree(false); dto.setValidDuration(12); dto.setCatalogue(List.of("chapter / section"));
        return dto;
    }
}
