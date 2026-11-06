package com.tianji.coursemcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.mcp.CourseDetailContract;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Real WebMVC MCP + real Feign over HTTP; upstream course data here is a fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tj.mcp.server.token=local-test-token-at-least-32-characters",
        "tj.mcp.server.allowed-course-ids=1589888774267072513,2,3,4",
        "spring.cloud.openfeign.client.config.course.readTimeout=300"
})
class CourseMcpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static HttpServer backend;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry properties) throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/courses/base-info/batch", exchange -> {
            BACKEND_CALLS.incrementAndGet();
            try {
                var ids = JSON.readValue(exchange.getRequestBody(), Long[].class);
                if (ids[0] == 3L) {
                    exchange.sendResponseHeaders(500, -1); return;
                }
                var dto = new CourseBaseInfoDTO(); dto.setId(ids[0] == 4L ? 99L : ids[0]);
                dto.setName("Service fixture"); dto.setPrice(null); dto.setFree(true);
                dto.setValidDuration(12); dto.setCatalogue(List.of("chapter / section"));
                byte[] bytes = JSON.writeValueAsBytes(ids[0] == 2L ? List.of() : List.of(dto));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        backend.start();
        properties.add("spring.cloud.openfeign.client.config.course.url", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() { if (backend != null) backend.stop(0); }

    @Test
    void realMcpAndFeignPreserveContractAndRejectUpstreamFailures() throws Exception {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .openConnectionOnStartup(false).customizeRequest(builder ->
                        builder.header("Authorization", "Bearer local-test-token-at-least-32-characters")).build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(3)).build()) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo(CourseDetailContract.SERVER_ID);
            assertThat(client.listTools().tools()).singleElement().satisfies(tool -> {
                assertThat(tool.name()).isEqualTo(CourseDetailContract.TOOL_NAME);
                assertThat(tool.annotations().readOnlyHint()).isTrue();
            });
            var found = client.callTool(new McpSchema.CallToolRequest(CourseDetailContract.TOOL_NAME,
                    Map.of("courseId", "1589888774267072513")));
            assertThat(found.isError()).isFalse();
            var envelope = JSON.readTree(((McpSchema.TextContent) found.content().get(0)).text());
            assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(envelope.path("data").path("id").isTextual()).isTrue();
            assertThat(envelope.path("data").path("id").asText()).isEqualTo("1589888774267072513");
            assertThat(envelope.path("data").path("price").isNull()).isTrue();
            assertThat(envelope.path("data").path("validDuration").asInt()).isEqualTo(12);
            var missing = client.callTool(new McpSchema.CallToolRequest(CourseDetailContract.TOOL_NAME, Map.of("courseId", "2")));
            assertThat(missing.isError()).isFalse();
            assertThat(((McpSchema.TextContent) missing.content().get(0)).text()).contains("NOT_FOUND");
            for (String id : List.of("3", "4")) {
                var failed = client.callTool(new McpSchema.CallToolRequest(CourseDetailContract.TOOL_NAME, Map.of("courseId", id)));
                assertThat(failed.isError()).isTrue();
                assertThat(((McpSchema.TextContent) failed.content().get(0)).text()).doesNotContain("NOT_FOUND", "http", "Exception");
            }
            int before = BACKEND_CALLS.get();
            var forbidden = client.callTool(new McpSchema.CallToolRequest(CourseDetailContract.TOOL_NAME, Map.of("courseId", "5")));
            assertThat(forbidden.isError()).isTrue();
            assertThat(((McpSchema.TextContent) forbidden.content().get(0)).text()).contains("FORBIDDEN");
            assertThat(BACKEND_CALLS).hasValue(before);
        }
    }

    @Test
    void requiresServiceAuthenticationAndRejectsBrowserOrigin() throws Exception {
        var http = HttpClient.newHttpClient();
        var uri = URI.create("http://127.0.0.1:" + port + "/mcp");
        for (String token : List.of("", "wrong")) {
            var request = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        var browser = HttpRequest.newBuilder(uri).header("Origin", "https://untrusted.example")
                .header("Authorization", "Bearer local-test-token-at-least-32-characters")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build();
        assertThat(http.send(browser, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
