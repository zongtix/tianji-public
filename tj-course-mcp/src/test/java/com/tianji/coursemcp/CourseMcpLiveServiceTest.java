package com.tianji.coursemcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.mcp.CourseDetailContract;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Opt-in, read-only real course service acceptance. Never runs in the default test suite. */
@EnabledIfSystemProperty(named = "mcp.live.enabled", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.openfeign.client.config.course.url=${mcp.live.course-url:http://127.0.0.1:8086}",
        "tj.mcp.server.allowed-course-ids=1589888774267072513,1589905661084430337"
})
class CourseMcpLiveServiceTest {
    private static final String TOKEN = randomToken();
    @LocalServerPort int port;
    @Autowired CourseClient courses;
    @Autowired ObjectMapper json;

    @org.springframework.test.context.DynamicPropertySource
    static void token(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("tj.mcp.server.token", () -> TOKEN);
    }

    @Test
    void actualCourseDetailsAgreeAcrossSingleBatchAndMcp() throws Exception {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .openConnectionOnStartup(false).customizeRequest(builder -> builder.header("Authorization", "Bearer " + TOKEN)).build();
        var evidence = new ArrayList<Map<String, Object>>();
        try (var client = McpClient.sync(transport).build()) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo(CourseDetailContract.SERVER_ID);
            assertThat(client.listTools().tools()).hasSize(1);
            for (long id : List.of(1589888774267072513L, 1589905661084430337L)) {
                var single = courses.getCourseBaseInfoById(id);
                var batch = courses.getBaseInfoBatch(List.of(id));
                assertThat(single).isNotNull();
                assertThat(batch).singleElement().usingRecursiveComparison().isEqualTo(single);
                long started = System.nanoTime();
                var result = client.callTool(new McpSchema.CallToolRequest(CourseDetailContract.TOOL_NAME, Map.of("courseId", Long.toString(id))));
                assertThat(result.isError()).isFalse();
                String text = ((McpSchema.TextContent) result.content().get(0)).text();
                var envelope = json.readValue(text, CourseDetailContract.Result.class);
                assertThat(envelope.status()).isEqualTo("FOUND");
                assertThat(envelope.data().toDto()).usingRecursiveComparison().isEqualTo(single);
                evidence.add(Map.of("courseId", Long.toString(id), "name", single.getName(),
                        "textBytes", text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        "catalogueItems", single.getCatalogue().size(), "fieldsEqual", true,
                        "callMs", (System.nanoTime() - started) / 1_000_000));
            }
        }
        Path directory = Path.of("target", "mcp-evaluation"); Files.createDirectories(directory);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("live-service.json").toFile(),
                Map.of("checkedAt", Instant.now().toString(), "layer", "real-course-service", "passed", true,
                        "modelRequests", 0, "courses", evidence));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
