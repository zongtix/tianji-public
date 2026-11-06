package com.tianji.aigc.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianji.aigc.config.McpProperties;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.mcp.CourseDetailContract;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7：带 Mcp-Session-Id 的本地 HTTP MCP 模拟，验证 SDK 0.17 在会话丢失/闪断后的恢复，
 * 以及单调用者取消/预算耗尽不得关闭共享健康连接。不接真实 8097。
 * <p>本文件未在本子代理中运行 Maven。</p>
 */
class RemainingMcpSessionRecoveryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpProperties settings = new McpProperties();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger initializeCount = new AtomicInteger();
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger unknownSessionResponses = new AtomicInteger();
    private final CountDownLatch callEntered = new CountDownLatch(1);
    private final CountDownLatch callRelease = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;
    private CourseMcpClient client;
    private volatile boolean delayCall;
    private volatile boolean rejectUnknownSessions = true;
    private volatile int transientCallFailuresLeft;
    private volatile boolean dropAllConnections;

    @BeforeEach
    void setup() throws Exception {
        settings.setRequestTimeout(Duration.ofSeconds(2));
        settings.setRoundBudget(Duration.ofSeconds(5));
        settings.setToken("remaining-mcp-session-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/mcp", exchange -> {
            try {
                if (dropAllConnections) {
                    exchange.close();
                    return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer " + settings.getToken());
                @SuppressWarnings("unchecked")
                var request = (Map<String, Object>) json.readValue(exchange.getRequestBody(), Map.class);
                requests.add(request);
                if (!request.containsKey("id")) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                String method = String.valueOf(request.get("method"));
                String sessionHeader = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                if (!"initialize".equals(method) && rejectUnknownSessions) {
                    if (sessionHeader == null || !sessions.contains(sessionHeader)) {
                        unknownSessionResponses.incrementAndGet();
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                }
                if ("tools/call".equals(method)) {
                    callCount.incrementAndGet();
                    if (delayCall) {
                        callEntered.countDown();
                        callRelease.await(4, TimeUnit.SECONDS);
                    }
                    if (transientCallFailuresLeft > 0) {
                        transientCallFailuresLeft--;
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                }
                Object response = switch (method) {
                    case "initialize" -> {
                        initializeCount.incrementAndGet();
                        yield Map.of("protocolVersion", "2025-06-18",
                                "capabilities", Map.of("tools", Map.of()),
                                "serverInfo", Map.of("name", CourseDetailContract.SERVER_ID, "version", "1.0.0"));
                    }
                    case "tools/list" -> {
                        var schema = json.readValue(CourseDetailContract.INPUT_SCHEMA, Map.class);
                        yield Map.of("tools", List.of(Map.of(
                                "name", CourseDetailContract.TOOL_NAME, "inputSchema", schema)));
                    }
                    case "tools/call" -> content(json.writeValueAsString(CourseDetailContract.Result.found(detail())), false);
                    default -> Map.of();
                };
                byte[] bytes = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", response));
                if ("initialize".equals(method)) {
                    String sessionId = "sess-" + UUID.randomUUID();
                    sessions.add(sessionId);
                    exchange.getResponseHeaders().add("Mcp-Session-Id", sessionId);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        settings.setCourseServerUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        client = new CourseMcpClient(settings, json, observations);
    }

    @AfterEach
    void cleanup() {
        callRelease.countDown();
        if (client != null) client.close();
        if (server != null) server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void unknownSessionAfterServerRestartIsRecoveredBySdkWithoutClosingHealthySharedConnection() {
        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
        assertThat(initializeCount).hasValue(1);
        assertThat(sessions).hasSize(1);

        // 模拟服务端重启：会话表清空，旧 Mcp-Session-Id 变为未知。
        sessions.clear();

        assertThatThrownBy(() -> client.read(detail().getId(), scope()))
                .isInstanceOfSatisfying(McpToolException.class,
                        error -> assertThat(error.kind()).isEqualTo(McpToolException.Kind.UNAVAILABLE));
        assertThat(unknownSessionResponses.get()).isGreaterThanOrEqualTo(1);

        // SDK 应在后续调用中重新 initialize；业务层不得因 call 失败关闭共享客户端后永久卡死。
        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
        assertThat(initializeCount.get()).isGreaterThanOrEqualTo(2);
        assertThat(callCount.get()).isGreaterThanOrEqualTo(2);
        assertThat(sessions).isNotEmpty();
    }

    @Test
    void briefCallTransportFailureDoesNotRequireProcessRestart() {
        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
        int initsAfterWarm = initializeCount.get();

        transientCallFailuresLeft = 1;
        assertThatThrownBy(() -> client.read(detail().getId(), scope()))
                .isInstanceOf(McpToolException.class);

        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
        // 短暂 503 不应迫使业务层重建整条共享连接；initialize 次数保持或仅因 SDK 会话恢复而增加。
        assertThat(initializeCount.get()).isGreaterThanOrEqualTo(initsAfterWarm);
        assertThat(callCount.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void droppedConnectionThenRestoreAllowsLaterCourseDetailRead() {
        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());

        dropAllConnections = true;
        assertThatThrownBy(() -> client.read(detail().getId(), scope()))
                .isInstanceOf(McpToolException.class);
        dropAllConnections = false;

        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
    }

    @Test
    void cancelledCallerDoesNotInvalidateSharedSessionForOthers() throws Exception {
        var active = new AtomicBoolean(true);
        delayCall = true;
        var pending = executor.submit(() -> client.read(detail().getId(), new McpCallScope(active::get, () -> true)));
        assertThat(callEntered.await(2, TimeUnit.SECONDS)).isTrue();
        active.set(false);
        assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(McpCallScope.CancelledException.class);
        callRelease.countDown();
        delayCall = false;

        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
        assertThat(initializeCount).hasValue(1);
    }

    @Test
    void budgetExhaustionOnOneScopeDoesNotCloseSharedHealthyConnection() throws Exception {
        settings.setRoundBudget(Duration.ofMillis(200));
        client.close();
        client = new CourseMcpClient(settings, json, observations);

        var warm = scope();
        assertThat(client.read(detail().getId(), warm).getId()).isEqualTo(detail().getId());
        Thread.sleep(220);
        assertThatThrownBy(() -> client.read(detail().getId(), warm))
                .isInstanceOfSatisfying(McpToolException.class,
                        error -> assertThat(error.kind()).isEqualTo(McpToolException.Kind.TIMEOUT));

        assertThat(client.read(detail().getId(), scope()).getId()).isEqualTo(detail().getId());
        assertThat(initializeCount).hasValue(1);
    }

    private McpCallScope scope() {
        return new McpCallScope(() -> true, () -> true);
    }

    private static Map<String, Object> content(String text, boolean error) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error);
    }

    private static CourseBaseInfoDTO detail() {
        var dto = new CourseBaseInfoDTO();
        dto.setId(1589888774267072513L);
        dto.setName("Session recovery fixture");
        dto.setPrice(14900);
        dto.setFree(false);
        dto.setValidDuration(12);
        dto.setCatalogue(List.of("chapter / section"));
        return dto;
    }
}
