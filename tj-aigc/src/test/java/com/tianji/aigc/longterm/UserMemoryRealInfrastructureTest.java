package com.tianji.aigc.longterm;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.service.ChatGenerationStore;
import com.tianji.aigc.vo.SessionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Only for the disposable, loopback-only acceptance instances created for this test. */
@EnabledIfEnvironmentVariable(named = "TJ_MEMORY_REAL_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=aigc-stream-test",
        "server.address=127.0.0.1",
        "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false", "tj.auth.resource.enable=false",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13316/memory_acceptance?characterEncoding=utf8&serverTimezone=Asia/Shanghai&connectTimeout=3000&socketTimeout=5000",
        "spring.datasource.username=root", "spring.datasource.password=", "spring.sql.init.mode=never",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=16391", "spring.data.redis.password=",
        "spring.ai.openai.api-key=test-only", "spring.ai.openai.base-url=http://127.0.0.1:1",
        "spring.ai.openai.chat.options.model=test-model", "tj.swagger.enableResponseWrap=true",
        "tj.ai.long-term-memory.enabled=true",
        "tj.ai.long-term-memory.encryption-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
        "spring.ai.chat.observations.log-prompt=false", "spring.ai.chat.observations.log-completion=false"
})
class UserMemoryRealInfrastructureTest {
    private static final long USER_A = 700001, USER_B = 800001;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentLinkedQueue<Prompt> answerPrompts = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean slowTitleStarted = new AtomicBoolean();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMemoryStore store;
    @Autowired SessionProperties sessions;
    @Autowired ChatGenerationStore generations;
    @Autowired ChatMemory chatMemory;
    @Autowired UserMemoryWorker worker;
    @MockBean ChatModel model;
    @MockBean NacosConfigManager nacos;
    @MockBean org.springframework.scheduling.TaskScheduler taskScheduler;

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13316);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("memory_acceptance");
        assertThat(jdbc.queryForObject("SELECT @@datadir", String.class).replace('\\', '/'))
                .contains("/work/memory-acceptance/mysql-data/");
        for (String table : List.of("user_memory_task", "user_memory_item", "user_memory_profile", "chat_message", "chat_session")) {
            jdbc.update("DELETE FROM " + table);
        }
        sessions.setExamples(List.of(new SessionVO.Example("测试", "测试问题")));
        answerPrompts.clear();
        slowTitleStarted.set(false);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            String system = prompt.getSystemMessage().getText();
            if (system.contains("从用户最近的课程咨询中")) {
                JsonNode input = json.readTree(prompt.getUserMessage().getText());
                for (JsonNode turn : input.path("turns")) {
                    if (turn.path("user").asText().contains("我学过一点 Java")) {
                        String body = json.writeValueAsString(java.util.Map.of("memories", List.of(java.util.Map.of(
                                "key", "background", "content", "学过一点 Java", "requestId", turn.path("requestId").asText(), "evidence", "学过一点 Java"))));
                        return Flux.just(reply(body));
                    }
                }
                return Flux.just(reply("{\"memories\":[]}"));
            }
            if (system.contains("意图路由器")) return Flux.just(reply("{\"agent\":\"KNOWLEDGE\",\"clarification\":\"\"}"));
            if (system.contains("生成便于查找的会话标题")) {
                if (prompt.getUserMessage().getText().contains("测试标题阶段停止")) {
                    return Flux.<ChatResponse>never().doOnSubscribe(ignored -> slowTitleStarted.set(true));
                }
                return Flux.just(reply("{\"title\":\"学习对话\"}"));
            }
            answerPrompts.add(prompt);
            if (prompt.getUserMessage().getText().contains("测试停止")) return Flux.concat(Flux.just(reply("已收到")), Flux.never());
            return Flux.just(reply("收到，你可以继续说明学习需求。"));
        });
    }

    @Test
    void realDatabaseRedisHttpAndModelStubSupportIsolatedCrossSessionMemory() throws Exception {
        String first = createSession(USER_A);
        chat(USER_A, first, "我学过一点 Java");
        chat(USER_A, first, "我有一些空闲时间");
        chat(USER_A, first, "帮我规划学习");
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            worker.poll();
            assertThat(store.list(USER_A)).extracting(UserMemoryStore.Memory::content).contains("学过一点 Java");
        });
        assertThat(jdbc.queryForObject("SELECT content_cipher FROM user_memory_item WHERE user_id=?", String.class, USER_A))
                .doesNotContain("Java");
        assertThat(get(USER_B, "/memories").path("data").size()).isZero();

        String next = createSession(USER_A);
        answerPrompts.clear();
        chat(USER_A, next, "继续解释概念");
        assertThat(answerPrompts).anySatisfy(prompt -> assertThat(prompt.getSystemMessage().getText()).contains("学过一点 Java"));
        String other = createSession(USER_B);
        answerPrompts.clear();
        chat(USER_B, other, "继续解释概念");
        assertThat(answerPrompts).isNotEmpty();
        assertThat(answerPrompts).allSatisfy(prompt -> assertThat(prompt.getSystemMessage().getText()).doesNotContain("学过一点 Java"));

        var item = store.list(USER_A).get(0);
        assertThat(request(USER_B, "PUT", "/memories/" + item.id(), "{\"version\":" + item.version() + ",\"content\":\"越权修改\"}").path("code").asInt()).isEqualTo(400);
        assertThat(request(USER_A, "PUT", "/memories/" + item.id(), "{\"version\":" + item.version() + ",\"content\":\"正在学 Java\"}").path("code").asInt()).isEqualTo(200);
        assertThat(store.list(USER_A)).extracting(UserMemoryStore.Memory::content).containsExactly("正在学 Java");
        assertThat(request(USER_A, "PUT", "/memories/settings", "{\"enabled\":false}").path("code").asInt()).isEqualTo(200);
        assertThat(store.context(USER_A)).isEmpty();
        assertThat(request(USER_A, "DELETE", "/memories", null).path("code").asInt()).isEqualTo(200);
        assertThat(store.list(USER_A)).isEmpty();
    }

    @Test
    void realRedisStopCancelsTheStreamAndDoesNotQueueThePartialTurn() throws Exception {
        long user = 700002;
        String session = createSession(user);
        HttpRequest request = HttpRequest.newBuilder(uri("/chat")).timeout(Duration.ofSeconds(15))
                .header("user-info", Long.toString(user)).header("x-request-from", "gateway")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of(
                        "sessionId", session, "question", "测试停止", "stopAcknowledgement", true)))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        String requestId = null;
        boolean stopped = false;
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                JsonNode event = json.readTree(line.substring(5));
                if (event.path("eventType").asInt() == 1003) requestId = event.path("eventData").path("requestId").asText();
                if (event.path("eventType").asInt() == 1001) {
                    assertThat(requestId).isNotBlank();
                    assertThat(request(user, "POST", "/chat/stop?sessionId=" + session + "&requestId=" + requestId, null).path("code").asInt()).isEqualTo(200);
                }
                if (event.path("eventType").asInt() == 1002) { stopped = true; break; }
            }
        }
        assertThat(stopped).isTrue();
        String finalRequestId = requestId;
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_memory_task WHERE request_id=?", Integer.class, finalRequestId)).isZero());
        assertThat(store.list(user)).isEmpty();
    }

    @Test
    void stopDuringTitleGenerationDiscardsPersistedMemoryCandidate() throws Exception {
        long user = 700004;
        String session = createSession(user);
        HttpRequest chat = HttpRequest.newBuilder(uri("/chat")).timeout(Duration.ofSeconds(15))
                .header("user-info", Long.toString(user)).header("x-request-from", "gateway")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of(
                        "sessionId", session, "question", "测试标题阶段停止", "stopAcknowledgement", true)))).build();
        var response = http.send(chat, HttpResponse.BodyHandlers.ofInputStream());
        String requestId = null;
        boolean stopped = false;
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                JsonNode event = json.readTree(line.substring(5));
                if (event.path("eventType").asInt() == 1003) requestId = event.path("eventData").path("requestId").asText();
                if (event.path("eventType").asInt() == 1001) {
                    assertThat(requestId).isNotBlank();
                    String id = requestId;
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(slowTitleStarted).isTrue();
                        assertThat(store.isPersisted(id, user + "_" + session)).isTrue();
                    });
                    assertThat(request(user, "POST", "/chat/stop?sessionId=" + session + "&requestId=" + requestId, null).path("code").asInt()).isEqualTo(200);
                }
                if (event.path("eventType").asInt() == 1002) { stopped = true; break; }
            }
        }
        assertThat(stopped).isTrue();
        String id = requestId;
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_memory_task WHERE request_id=?", Integer.class, id)).isZero());
        assertThat(store.list(user)).isEmpty();
        assertThat(chatMemory.get(user + "_" + session)).extracting(org.springframework.ai.chat.messages.Message::getText)
                .contains("测试标题阶段停止", "收到，你可以继续说明学习需求。");
    }

    @Test
    void mysqlConcurrentDuplicateAndLeaseClaimsDoNotCreateDuplicateWork() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 10; round++) {
                long user = 700003 + round;
                String session = UUID.randomUUID().toString().replace("-", ""), request = UUID.randomUUID().toString();
                var start = new CountDownLatch(1);
                var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> { start.await(); return store.stage(user, session, request, "我想学 Java", null); }));
                start.countDown();
                for (var future : futures) assertThat(future.get()).isTrue();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task WHERE request_id=?", Integer.class, request)).isEqualTo(1);
                assertThat(makeReady(request, user + "_" + session)).isTrue();
                var gate = new CountDownLatch(1);
                var claims = new ArrayList<java.util.concurrent.Future<UserMemoryStore.Batch>>();
                for (int i = 0; i < 4; i++) claims.add(pool.submit(() -> { gate.await(); return store.claimDue(1, Duration.ZERO); }));
                gate.countDown();
                var claimed = new ArrayList<UserMemoryStore.Batch>();
                for (var future : claims) { var batch = future.get(); if (batch != null && batch.userId() == user) claimed.add(batch); }
                assertThat(claimed).hasSize(1);
                assertThat(store.apply(claimed.get(0), List.of(new UserMemoryStore.Candidate("goal", "想学 Java", request, "想学 Java")))).isTrue();
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void mysqlDeleteAllWinsRegardlessOfWhetherClaimedExtractionFinishesFirst() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                long user = 720000 + round;
                String session = UUID.randomUUID().toString().replace("-", ""), request = UUID.randomUUID().toString();
                assertThat(store.stage(user, session, request, "我想学后端", null)).isTrue();
                assertThat(makeReady(request, user + "_" + session)).isTrue();
                var batch = store.claimDue(1, Duration.ZERO);
                assertThat(batch).isNotNull();
                var gate = new CountDownLatch(1);
                var apply = pool.submit(() -> { gate.await(); return store.apply(batch, List.of(
                        new UserMemoryStore.Candidate("goal", "想学后端", request, "想学后端"))); });
                var delete = pool.submit(() -> { gate.await(); store.deleteAll(user); return null; });
                gate.countDown();
                apply.get(); delete.get();
                assertThat(store.list(user)).isEmpty();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task WHERE user_id=?", Integer.class, user)).isZero();
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void stopWinsTheRedisFenceEvenWhenTheAssistantAnswerWasJustPersisted() {
        long user = 740001;
        String session = UUID.randomUUID().toString().replace("-", ""), conversation = user + "_" + session;
        var generation = generations.acquire(session);
        try {
            assertThat(store.stage(user, session, generation.requestId(), "我想学 Java", null)).isTrue();
            org.mockito.Mockito.doReturn(Flux.just(reply("回答"))).when(model).stream(any(Prompt.class));
            var client = ChatClient.builder(model).defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build();
            client.prompt().system("test").user("我想学 Java")
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation))
                    .advisors(new UserMemoryCompletionAdvisor(generation.requestId(), () -> {
                        boolean wasActive = generations.renew(generation);
                        assertThat(generations.stop(session, generation.requestId())).isTrue();
                        return wasActive;
                    })).stream().content().collectList().block(Duration.ofSeconds(5));
            assertThat(store.isPersisted(generation.requestId(), conversation)).isTrue();
            assertThat(store.claimDue(1, Duration.ZERO)).isNull();
            assertThat(generations.complete(generation)).isFalse();
            store.abandon(generation.requestId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task WHERE request_id=?", Integer.class,
                    generation.requestId())).isZero();
        } finally { generations.release(generation); }
    }

    @Test
    void completedRequestRejectsLateStopBeforeThePersistedTaskBecomesReady() {
        long user = 740002;
        String session = UUID.randomUUID().toString().replace("-", ""), conversation = user + "_" + session;
        var generation = generations.acquire(session);
        try {
            assertThat(store.stage(user, session, generation.requestId(), "我想学 Java", null)).isTrue();
            assertThat(store.markPersisted(generation.requestId(), conversation)).isTrue();
            assertThat(generations.complete(generation)).isTrue();
            assertThat(generations.stop(session, generation.requestId())).isFalse();
            assertThat(store.markReady(generation.requestId(), conversation)).isTrue();
            assertThat(store.claimDue(1, Duration.ZERO)).isNotNull();
        } finally { generations.release(generation); }
    }

    private String createSession(long user) throws Exception {
        return request(user, "POST", "/session?n=0", null).path("data").path("sessionId").asText();
    }
    private void chat(long user, String session, String question) throws Exception {
        var result = raw(user, "POST", "/chat", json.writeValueAsString(java.util.Map.of("sessionId", session, "question", question)));
        assertThat(result).contains("eventType", "1001", "1002");
    }
    private JsonNode get(long user, String path) throws Exception { return request(user, "GET", path, null); }
    private JsonNode request(long user, String method, String path, String body) throws Exception {
        String result = raw(user, method, path, body);
        return result.isBlank() ? json.createObjectNode().put("code", 200) : json.readTree(result);
    }
    private String raw(long user, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(12))
                .header("user-info", Long.toString(user)).header("x-request-from", "gateway")
                .header("Content-Type", "application/json");
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static ChatResponse reply(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private boolean makeReady(String requestId, String conversationId) {
        return store.markPersisted(requestId, conversationId) && store.markReady(requestId, conversationId);
    }

}
