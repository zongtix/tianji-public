package com.tianji.aigc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=aigc-contract", "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false", "spring.datasource.url=jdbc:h2:mem:aigc;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql", "tj.ai.api-key=test-only",
        "tj.auth.resource.enable=true"})
@Timeout(45)
class AigcContractTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean ChatModel chatModel;
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void resetDatabase() {
        jdbc.update("DELETE FROM chat_record");
        jdbc.update("DELETE FROM chat_session");
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("你好，Java"), chunk("学习者")));
    }

    ChatResponse chunk(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    String id() { return UUID.randomUUID().toString().replace("-", ""); }
    HttpRequest request(String method, String path, String body, Long user) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("x-request-from", "gateway");
        if (user != null) { builder.header("user-info", user.toString()); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    JsonNode call(String method, String path, String body, Long user) throws Exception {
        return json.readTree(client.send(request(method, path, body, user), HttpResponse.BodyHandlers.ofString()).body());
    }
    String session(long user) throws Exception { return call("POST", "/session", null, user).path("data").path("id").asText(); }
    String payload(String session, String request, String question) throws Exception {
        return json.writeValueAsString(java.util.Map.of("sessionId", session, "requestId", request, "question", question));
    }
    String chat(String session, String request, String question, long user) throws Exception {
        return client.send(request("POST", "/chat", payload(session, request, question), user),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test void gatewaySseIsUnwrappedAndHistorySurvivesNewRequests() throws Exception {
        String session = session(1);
        String body = chat(session, id(), "解释 Java", 1);
        assertThat(body).contains("event:delta", "event:done", "\"status\":\"complete\"").doesNotContain("\"code\"");
        JsonNode turns = call("GET", "/session/" + session, null, 1L).path("data");
        assertThat(turns.size()).isEqualTo(1);
        assertThat(turns.get(0).path("answer").asText()).isEqualTo("你好，Java学习者");
        assertThat(call("GET", "/session/history", null, 1L).path("data").get(0).path("title").asText()).isEqualTo("解释 Java");
    }

    @Test void promptIncludesOnlyOwnedHistoryInOrder() throws Exception {
        String first = session(1);
        chat(first, id(), "我的标记是 maple", 1);
        chat(first, id(), "回忆标记", 1);
        var captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).stream(captor.capture());
        var messages = captor.getAllValues().get(1).getInstructions();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(messages.get(1).getText()).contains("maple");
        assertThat(messages.get(2).getText()).isEqualTo("你好，Java学习者");
        chat(session(2), id(), "新用户", 2);
        verify(chatModel, times(3)).stream(captor.capture());
        assertThat(captor.getValue().getInstructions()).hasSize(2);
    }

    @Test void crossUserCannotReadGenerateStopOrDelete() throws Exception {
        String session = session(1), request = id();
        assertThat(call("GET", "/session/" + session, null, 2L).path("code").asInt()).isEqualTo(400);
        assertThat(call("POST", "/chat", payload(session, request, "侵入"), 2L).path("code").asInt()).isEqualTo(400);
        assertThat(call("POST", "/chat/stop?sessionId=" + session + "&requestId=" + request, null, 2L).path("code").asInt()).isEqualTo(400);
        assertThat(call("DELETE", "/session/history?sessionId=" + session, null, 2L).path("code").asInt()).isEqualTo(400);
        assertThat(call("GET", "/session/history", null, 2L).path("data").size()).isZero();
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test void anonymousAndInvalidInputNeverReachProvider() throws Exception {
        assertThat(client.send(request("POST", "/session", null, null), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        String session = session(1);
        for (String question : List.of(" ", "a".repeat(4001))) {
            assertThat(call("POST", "/chat", payload(session, id(), question), 1L).path("code").asInt()).isEqualTo(400);
        }
        assertThat(call("POST", "/chat", payload(session, "bad", "hi"), 1L).path("code").asInt()).isEqualTo(400);
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test void repeatedRequestIdDoesNotBillAgain() throws Exception {
        String session = session(1), request = id();
        chat(session, request, "hi", 1);
        assertThat(call("POST", "/chat", payload(session, request, "hi"), 1L).path("code").asInt()).isEqualTo(400);
        verify(chatModel, times(1)).stream(any(Prompt.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_record", Integer.class)).isEqualTo(1);
    }

    @Test void providerFailureIsPersistedAndDoesNotLeakItsMessage() throws Exception {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(chunk("部分")),
                Flux.error(new IllegalStateException("secret-provider-detail"))));
        String session = session(1);
        String body = chat(session, id(), "hi", 1);
        assertThat(body).contains("\"status\":\"error\"").doesNotContain("secret-provider-detail");
        assertThat(jdbc.queryForObject("SELECT answer FROM chat_record", String.class)).isEqualTo("部分");
        assertThat(jdbc.queryForObject("SELECT status FROM chat_record", String.class)).isEqualTo("error");
        assertThat(call("DELETE", "/session/history?sessionId=" + session, null, 1L).path("code").asInt()).isEqualTo(200);
    }

    @Test void stopCancelsSilentUpstreamAndStaleStopCannotCancelNextTurn() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(chunk("部分答案")), Flux.never())
                .doOnCancel(() -> cancelled.set(true)));
        String session = session(1), requestId = id();
        var response = client.send(request("POST", "/chat", payload(session, requestId, "长问题"), 1L), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            while (!"event:delta".equals(reader.readLine())) { }
            assertThat(call("POST", "/chat", payload(session, id(), "并发"), 1L).path("code").asInt()).isEqualTo(400);
            assertThat(call("DELETE", "/session/history?sessionId=" + session, null, 1L).path("code").asInt()).isEqualTo(400);
            call("POST", "/chat/stop?sessionId=" + session + "&requestId=" + requestId, null, 1L);
            assertThat(reader.lines().collect(java.util.stream.Collectors.joining())).contains("stopped");
        }
        assertThat(cancelled.get()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_record", String.class)).isEqualTo("stopped");
        cancelled.set(false);
        String next = id();
        var nextResponse = client.send(request("POST", "/chat", payload(session, next, "继续"), 1L), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(nextResponse.body(), StandardCharsets.UTF_8))) {
            while (!"event:delta".equals(reader.readLine())) { }
            call("POST", "/chat/stop?sessionId=" + session + "&requestId=" + requestId, null, 1L);
            assertThat(cancelled.get()).isFalse();
            call("POST", "/chat/stop?sessionId=" + session + "&requestId=" + next, null, 1L);
            assertThat(reader.lines().collect(java.util.stream.Collectors.joining())).contains("stopped");
        }
        assertThat(cancelled.get()).isTrue();
    }

    @Test void deleteRemovesTurnsAtomicallyAndSessionBecomesUnavailable() throws Exception {
        String session = session(1);
        chat(session, id(), "hi", 1);
        call("DELETE", "/session/history?sessionId=" + session, null, 1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_session", Integer.class)).isZero();
        assertThat(call("GET", "/session/" + session, null, 1L).path("code").asInt()).isEqualTo(400);
    }

    @Test void disconnectCancelsProviderAndPreservesPartialAnswer() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.interval(Duration.ofMillis(100))
                .map(tick -> chunk("持续输出" )).doOnCancel(() -> cancelled.set(true)));
        String session = session(1);
        var response = client.send(request("POST", "/chat", payload(session, id(), "断开连接"), 1L), HttpResponse.BodyHandlers.ofInputStream());
        var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        while (!"event:delta".equals(reader.readLine())) { }
        response.body().close();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilTrue(cancelled);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM chat_record", String.class)).isEqualTo("stopped"));
        assertThat(jdbc.queryForObject("SELECT answer FROM chat_record", String.class)).contains("持续输出");
    }
}
