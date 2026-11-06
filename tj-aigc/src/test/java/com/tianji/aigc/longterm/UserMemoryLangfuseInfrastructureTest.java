package com.tianji.aigc.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Opt-in test: disposable MySQL/Redis, test identity and synthetic model only. */
@EnabledIfEnvironmentVariable(named = "TJ_MEMORY_OTLP_FLOW", matches = "true")
@TestPropertySource(properties = {
        "tj.ai.knowledge.observation.langfuse.enabled=true",
        "tj.ai.knowledge.observation.langfuse.endpoint=${TJ_TRACE_SYNTHETIC_ENDPOINT}",
        "tj.ai.knowledge.observation.langfuse.public-key=${TJ_TRACE_SYNTHETIC_PUBLIC_KEY}",
        "tj.ai.knowledge.observation.langfuse.secret-key=${TJ_TRACE_SYNTHETIC_SECRET_KEY}",
        "tj.ai.observation.conversation-content=true", "tj.ai.observation.memory-details=true",
        "tj.ai.observation.model-content=true", "tj.ai.observation.synthetic-evaluation=true",
        "management.tracing.sampling.probability=1.0"
})
class UserMemoryLangfuseInfrastructureTest extends UserMemoryRealInfrastructureTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired ObservationRegistry registry;
    @Autowired SdkTracerProvider tracerProvider;

    @BeforeEach
    @Override
    void setup() {
        assertThat(System.getenv("TJ_TRACE_SYNTHETIC_ENDPOINT"))
                .isEqualTo("http://127.0.0.1:3000/api/public/otel/v1/traces");
        super.setup();
        reset(model);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            String system = prompt.getSystemMessage().getText();
            String result;
            if (system.contains("从用户最近的课程咨询中")) {
                JsonNode input = json.readTree(prompt.getUserMessage().getText());
                JsonNode turn = input.path("turns").get(0);
                result = json.writeValueAsString(Map.of("memories", List.of(Map.of("key", "background",
                        "content", "合成学员学过 Java", "requestId", turn.path("requestId").asText(),
                        "evidence", "合成学员学过 Java"))));
            } else if (system.contains("意图路由器")) {
                result = "{\"agent\":\"KNOWLEDGE\",\"clarification\":\"\"}";
            } else if (system.contains("生成便于查找的会话标题")) {
                result = "{\"title\":\"合成测试\"}";
            } else {
                result = "合成回答：可以继续学习 Java。";
            }
            var response = new ChatResponse(List.of(new Generation(new AssistantMessage(result))));
            return Flux.defer(() -> {
                var context = ChatModelObservationContext.builder().prompt(prompt).provider("synthetic").build();
                var observation = Observation.createNotStarted("gen_ai.client.operation", () -> context, registry)
                        .contextualName("synthetic-model").start();
                return Flux.just(response).doOnNext(context::setResponse).doFinally(signal -> observation.stop())
                        .contextWrite(values -> values.put(ObservationThreadLocalAccessor.KEY, observation));
            });
        });
    }

    @Test
    void isolatedSpringServiceExportsSyntheticMemoryAndConversation() throws Exception {
        long user = 700001;
        String first = request(user, "POST", "/session?n=0", null).path("data").path("sessionId").asText();
        chat(user, first, "合成学员学过 Java，请简单回答。");
        chat(user, first, "合成学员希望继续学习。");
        chat(user, first, "合成学员想了解学习建议。");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            worker.poll();
            assertThat(store.list(user)).extracting(UserMemoryStore.Memory::content).contains("合成学员学过 Java");
        });
        String second = request(user, "POST", "/session?n=0", null).path("data").path("sessionId").asText();
        chat(user, second, "合成追问：我先前的基础是什么？");
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        System.out.println("SYNTHETIC_SESSIONS=" + first + "," + second);
    }

    private void chat(long user, String session, String text) throws Exception {
        String response = raw(user, "POST", "/chat", json.writeValueAsString(Map.of("sessionId", session, "question", text)));
        assertThat(response).contains("1001", "1002", "合成回答");
    }
    private JsonNode request(long user, String method, String path, String body) throws Exception {
        return json.readTree(raw(user, method, path, body));
    }
    private String raw(long user, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("user-info", Long.toString(user)).header("x-request-from", "gateway")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
