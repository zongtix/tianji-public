package com.tianji.aigc.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Disposable loopback infrastructure only; both model inputs and users are synthetic. */
@EnabledIfEnvironmentVariable(named = "TJ_MEMORY_LIVE_FLOW", matches = "true")
class UserMemoryLiveFlowTest extends UserMemoryRealInfrastructureTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final ConcurrentLinkedQueue<int[]> usage = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> answerSystems = new ConcurrentLinkedQueue<>();

    @BeforeEach
    @Override
    void setup() {
        super.setup();
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertThat(key).isNotBlank();
        var api = OpenAiApi.builder().baseUrl("https://api.deepseek.com")
                .completionsPath("/chat/completions").apiKey(key).build();
        ChatModel delegate = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").streamUsage(true)
                        .extraBody(Map.of("thinking", Map.of("type", "disabled"))).build()).build();
        reset(model);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            String system = prompt.getSystemMessage().getText();
            if (system.contains("意图路由器")) return Flux.just(reply("{\"agent\":\"KNOWLEDGE\",\"clarification\":\"\"}"));
            if (system.contains("生成便于查找的会话标题")) return Flux.just(reply("{\"title\":\"学习对话\"}"));
            if (!system.contains("从用户最近的课程咨询中")) answerSystems.add(system);
            modelCalls.incrementAndGet();
            var callUsage = new AtomicReference<int[]>();
            return delegate.stream(prompt).doOnNext(response -> {
                var value = response.getMetadata().getUsage();
                if (value != null && value.getTotalTokens() != null && value.getTotalTokens() > 0
                        && value.getPromptTokens() != null && value.getCompletionTokens() != null)
                    callUsage.set(new int[]{value.getPromptTokens(), value.getCompletionTokens()});
            }).doFinally(signal -> { if (callUsage.get() != null) usage.add(callUsage.get()); });
        });
    }

    @Test
    void syntheticConversationUsesRealModelAcrossSessions() throws Exception {
        try {
            String first = createSession(700001);
            chat(700001, first, "我用 Java 写过两个月小练习。请简单回应。 ");
            chat(700001, first, "我想学习后端开发。请简单回应。 ");
            chat(700001, first, "我更喜欢文字教程。请简单回应。 ");
            await().atMost(Duration.ofSeconds(50)).untilAsserted(() -> {
                worker.poll();
                assertThat(store.list(700001)).extracting(UserMemoryStore.Memory::key).contains("background");
            });
            var before = store.list(700001);
            assertThat(before).anySatisfy(item -> assertThat(item.content()).contains("Java"));
            assertThat(store.list(800001)).isEmpty();

            String next = createSession(700001);
            answerSystems.clear();
            String answer = chat(700001, next, "我之前告诉过你我有什么编程基础？请简短复述。");
            assertThat(answerSystems).anySatisfy(system -> assertThat(system).contains("Java"));
            assertThat(answer).containsIgnoringCase("Java");

            var item = before.stream().filter(value -> value.key().equals("background")).findFirst().orElseThrow();
            JsonNode corrected = request(700001, "PUT", "/memories/" + item.id(),
                    json.writeValueAsString(Map.of("version", item.version(), "content", "学过 Java，目前正在练习")));
            assertThat(corrected.path("code").asInt()).isEqualTo(200);
            assertThat(store.list(700001)).anySatisfy(value -> assertThat(value.content()).isEqualTo("学过 Java，目前正在练习"));
            assertThat(request(700001, "DELETE", "/memories", null).path("code").asInt()).isEqualTo(200);
            assertThat(store.list(700001)).isEmpty();
            System.out.println("LIVE_FLOW_OK memories=" + before.stream().map(UserMemoryStore.Memory::content).toList());
            System.out.println("LIVE_FLOW_ANSWER=" + answer);
        } finally {
            writeUsage("live-flow-usage.json");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TJ_MEMORY_DEMO_DIR", matches = ".+")
    void browserDemoHarness() throws Exception {
        Path dir = Path.of(System.getenv("TJ_MEMORY_DEMO_DIR"));
        Files.createDirectories(dir);
        var poller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            poller.scheduleWithFixedDelay(worker::poll, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
            Files.writeString(dir.resolve("backend-port.txt"), Integer.toString(port));
            long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
            while (!Files.exists(dir.resolve("done")) && System.nanoTime() < deadline) Thread.sleep(250);
            assertThat(Files.exists(dir.resolve("done"))).isTrue();
        } finally {
            poller.shutdownNow();
            writeUsage("live-browser-usage.json");
        }
    }

    private void writeUsage(String name) throws Exception {
        Path file = Path.of("target", "long-memory-evaluation", name);
        Files.createDirectories(file.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), Map.of("model", "deepseek-flash",
                "syntheticOnly", true, "callsAttempted", modelCalls.get(), "usageResponses", usage.size(),
                "inputTokens", usage.stream().mapToInt(v -> v[0]).sum(), "outputTokens", usage.stream().mapToInt(v -> v[1]).sum()));
    }

    private String createSession(long user) throws Exception { return request(user, "POST", "/session?n=0", null).path("data").path("sessionId").asText(); }
    private String chat(long user, String session, String question) throws Exception {
        String body = json.writeValueAsString(Map.of("sessionId", session, "question", question));
        String result = raw(user, "POST", "/chat", body);
        assertThat(result).contains("eventType", "1001", "1002");
        return result;
    }
    private JsonNode request(long user, String method, String path, String body) throws Exception { return json.readTree(raw(user, method, path, body)); }
    private String raw(long user, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(60))
                .header("user-info", Long.toString(user)).header("x-request-from", "gateway")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
    private static ChatResponse reply(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}
