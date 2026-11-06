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
 * M9 / L7：本地等待与远端归属解耦、started/budget 跨线程安全发布。
 * <p>本文件未在本子代理中运行 Maven。</p>
 */
class RemainingMcpCallScopeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpProperties settings = new McpProperties();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private CourseMcpClient client;

    @BeforeEach
    void setup() throws Exception {
        settings.setRequestTimeout(Duration.ofSeconds(2));
        settings.setRoundBudget(Duration.ofSeconds(5));
        settings.setToken("remaining-scope-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/mcp", exchange -> {
            try {
                @SuppressWarnings("unchecked")
                var request = (Map<String, Object>) json.readValue(exchange.getRequestBody(), Map.class);
                if (!request.containsKey("id")) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                String method = String.valueOf(request.get("method"));
                if ("tools/call".equals(method)) {
                    entered.countDown();
                    release.await(4, TimeUnit.SECONDS);
                }
                Object response = switch (method) {
                    case "initialize" -> Map.of("protocolVersion", "2025-06-18",
                            "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", CourseDetailContract.SERVER_ID, "version", "1.0.0"));
                    case "tools/list" -> {
                        var schema = json.readValue(CourseDetailContract.INPUT_SCHEMA, Map.class);
                        yield Map.of("tools", List.of(Map.of(
                                "name", CourseDetailContract.TOOL_NAME, "inputSchema", schema)));
                    }
                    case "tools/call" -> Map.of(
                            "content", List.of(Map.of("type", "text",
                                    "text", json.writeValueAsString(CourseDetailContract.Result.found(detail())))),
                            "isError", false);
                    default -> Map.of();
                };
                byte[] bytes = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", response));
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
        release.countDown();
        if (client != null) client.close();
        if (server != null) server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void awaitSamplesRemoteOwnershipFarLessOftenThanLocalDeadlinePoll() throws Exception {
        var remoteChecks = new AtomicInteger();
        var scope = new McpCallScope(() -> {
            remoteChecks.incrementAndGet();
            return true;
        }, () -> {
            remoteChecks.incrementAndGet();
            return true;
        });
        var pending = executor.submit(() -> client.read(detail().getId(), scope));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        // 卡住约 900ms：旧实现约 900/25*2 ≈ 72 次 Redis 对；新实现应远小于本地 25ms 轮询次数。
        Thread.sleep(900);
        release.countDown();
        assertThat(pending.get(2, TimeUnit.SECONDS).getId()).isEqualTo(detail().getId());

        int remotes = remoteChecks.get();
        // stage 边界 + 结果后检查 + ~250ms 采样；900ms 等待下应明显小于每 25ms 成对调用的量级。
        assertThat(remotes).isLessThan(40);
        assertThat(remotes).isGreaterThan(0);
    }

    @Test
    void stopStillObservedWithinOwnershipCadence() throws Exception {
        var active = new AtomicBoolean(true);
        var scope = new McpCallScope(active::get, () -> true);
        var pending = executor.submit(() -> client.read(detail().getId(), scope));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        active.set(false);
        assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(McpCallScope.CancelledException.class);
        release.countDown();
    }

    @Test
    void startedAndBudgetAreVisiblyPublishedAcrossThreads() throws Exception {
        var scope = new McpCallScope(() -> true, () -> true);
        var startGate = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var failures = new CopyOnWriteArrayList<Throwable>();
        var readers = new CountDownLatch(8);

        for (int i = 0; i < 8; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(2, TimeUnit.SECONDS);
                    started.await(2, TimeUnit.SECONDS);
                    for (int n = 0; n < 200; n++) {
                        scope.checkLocalDeadline();
                        scope.check();
                    }
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    readers.countDown();
                }
            });
        }

        startGate.countDown();
        scope.start(Duration.ofSeconds(5));
        started.countDown();
        assertThat(readers.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
    }

    @Test
    void concurrentStartAndCheckDoNotFalseTimeoutFromPartialPublication() throws Exception {
        var falseTimeouts = new AtomicInteger();
        var otherErrors = new CopyOnWriteArrayList<Throwable>();
        var rounds = new CountDownLatch(200);
        for (int i = 0; i < 200; i++) {
            executor.submit(() -> {
                try {
                    var scope = new McpCallScope(() -> true, () -> true);
                    var starter = executor.submit(() -> scope.start(Duration.ofSeconds(2)));
                    for (int n = 0; n < 50; n++) {
                        try {
                            scope.checkLocalDeadline();
                        } catch (McpToolException timeout) {
                            if (timeout.kind() == McpToolException.Kind.TIMEOUT) {
                                // 仅在 start 已发布后极短预算才可能；2s 预算下不应出现。
                                falseTimeouts.incrementAndGet();
                            } else {
                                otherErrors.add(timeout);
                            }
                        } catch (McpCallScope.CancelledException ignored) {
                        }
                    }
                    starter.get(1, TimeUnit.SECONDS);
                    scope.checkLocalDeadline();
                } catch (Throwable error) {
                    otherErrors.add(error);
                } finally {
                    rounds.countDown();
                }
            });
        }
        assertThat(rounds.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(otherErrors).isEmpty();
        assertThat(falseTimeouts).hasValue(0);
    }

    private static CourseBaseInfoDTO detail() {
        var dto = new CourseBaseInfoDTO();
        dto.setId(1589888774267072513L);
        dto.setName("Scope fixture");
        dto.setPrice(14900);
        dto.setFree(false);
        dto.setValidDuration(12);
        dto.setCatalogue(List.of("chapter / section"));
        return dto;
    }
}
