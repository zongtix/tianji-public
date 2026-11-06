package com.tianji.aigc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=aigc-stream-test",
        "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false", "tj.auth.resource.enable=false",
        "spring.datasource.url=jdbc:h2:mem:aigc_stream;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=never",
        "spring.ai.openai.api-key=test-only", "spring.ai.openai.base-url=http://127.0.0.1:1",
        "spring.ai.openai.chat.options.model=test-model", "tj.swagger.enableResponseWrap=true"
})
class ChatStreamingContractTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockBean ChatService chatService;
    @MockBean com.tianji.aigc.service.ChatSessionService chatSessionService;
    @MockBean SystemPromptConfig systemPromptConfig;

    @org.junit.jupiter.api.Test
    void acknowledgementAndBoundStopArePreservedThroughHttp() throws Exception {
        String id = "123e4567-e89b-42d3-a456-426614174000";
        when(chatService.chat("test", "session-check", true)).thenReturn(Flux.just(
                ChatEventVO.builder().eventType(1003).eventData(java.util.Map.of("state", "ready", "requestId", id)).build(),
                ChatEventVO.builder().eventType(1002).build()));
        var client = HttpClient.newHttpClient();
        var chat = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("x-request-from", "gateway")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"test\",\"sessionId\":\"session-check\",\"stopAcknowledgement\":true}"))
                .build();
        var response = client.send(chat, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"requestId\":\"" + id + "\"", "\"state\":\"ready\"");
        when(chatService.stop("session-check", id)).thenReturn(false);
        var stop = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat/stop?sessionId=session-check&requestId=" + id))
                .timeout(Duration.ofSeconds(10)).header("x-request-from", "gateway")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var stopped = client.send(stop, HttpResponse.BodyHandlers.ofString());
        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(json.readTree(stopped.body()).path("code").asInt()).isEqualTo(400);
        verify(chatService).stop("session-check", id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void chatEmitsJsonEventsAndStopsWithoutResponseWrapping(boolean fromGateway) throws Exception {
        when(chatService.chat("解释 Java", "session-check"))
                .thenReturn(Flux.just(
                        ChatEventVO.builder().eventType(ChatEventTypeEnum.DATA.getValue()).eventData("你好").build(),
                        ChatEventVO.builder().eventType(ChatEventTypeEnum.DATA.getValue()).eventData("第一行\n第二行").build(),
                        ChatEventVO.builder().eventType(ChatEventTypeEnum.COURSE_CARDS.getValue())
                                .eventData(java.util.Map.of("cards", List.of(java.util.Map.of(
                                        "courseId", "9007199254740993", "name", "Java", "priceInCents", 1999)))).build(),
                        ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build()));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"question":"解释 Java","sessionId":"session-check"}
                        """));
        if (fromGateway) {
            requestBuilder.header("x-request-from", "gateway");
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        assertThat(response.body()).doesNotContain("\"code\"", "[DONE]");
        List<JsonNode> events = new ArrayList<>();
        for (String line : response.body().split("\\R")) {
            if (line.startsWith("data:")) events.add(json.readTree(line.substring(5)));
        }
        assertThat(events).hasSize(4);
        assertThat(events.get(0).path("eventType").asInt()).isEqualTo(1001);
        assertThat(events.get(0).path("eventData").asText()).isEqualTo("你好");
        assertThat(events.get(1).path("eventType").asInt()).isEqualTo(1001);
        assertThat(events.get(1).path("eventData").asText()).isEqualTo("第一行\n第二行");
        assertThat(events.get(2).path("eventType").asInt()).isEqualTo(1004);
        assertThat(events.get(2).path("eventData").path("cards").get(0).path("courseId").asText())
                .isEqualTo("9007199254740993");
        assertThat(events.get(3).path("eventType").asInt()).isEqualTo(1002);
        assertThat(events.get(3).hasNonNull("eventData")).isFalse();
        verify(chatService).chat("解释 Java", "session-check");
    }
}
