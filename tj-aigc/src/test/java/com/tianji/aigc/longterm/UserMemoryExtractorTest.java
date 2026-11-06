package com.tianji.aigc.longterm;

import com.tianji.aigc.config.UserMemoryConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserMemoryExtractorTest {
    @Test
    void extractionUsesOnlyBoundedTurnDataWithoutBusinessTools() {
        ChatModel model = mock(ChatModel.class);
        String request = UUID.randomUUID().toString();
        String result = "{\"memories\":[{\"key\":\"background\",\"content\":\"学过 Java\",\"requestId\":\"" + request + "\",\"evidence\":\"学过 Java\"}]}";
        int split = result.length() / 2;
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(result.substring(0, split))))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(result.substring(split)))))));
        var batch = new UserMemoryStore.Batch(7, "session-a", 0, "token",
                List.of(new UserMemoryStore.Turn(1, request, "我学过 Java", "以前接触过编程吗？")), List.of(), 1);
        var candidates = new UserMemoryExtractor(model).extract(batch);
        assertThat(candidates).extracting(UserMemoryStore.Candidate::content).containsExactly("学过 Java");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(captured.capture());
        assertThat(captured.getValue().getUserMessage().getText()).contains("我学过 Java", "以前接触过编程吗？");
        var options = (OpenAiChatOptions) captured.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("none");
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getMaxTokens()).isEqualTo(2048);
        assertThat(options.getStreamUsage()).isTrue();
        verifyNoMoreInteractions(model);
    }

    @Test
    void extractionReturnsSeveralFactsAndAnExplicitTargetWithExistingIds() throws Exception {
        ChatModel model = mock(ChatModel.class);
        String request = UUID.randomUUID().toString();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String result = json.writeValueAsString(java.util.Map.of("memories", List.of(
                new UserMemoryStore.Candidate("background", "学过 Python", request, "学过 Python"),
                new UserMemoryStore.Candidate("background", "看过微服务", request, "看过微服务"),
                new UserMemoryStore.Candidate("background", "只学过几天 Java", request, "只学过几天 Java", "11"),
                new UserMemoryStore.Candidate("preference", "喜欢项目练习", request, "喜欢项目练习"))));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(result))))));
        var existing = List.of(
                new UserMemoryStore.Memory(11, "background", "有 Java 基础", 1, 1, "old", request, java.time.LocalDateTime.now()),
                new UserMemoryStore.Memory(12, "background", "学过 C", 1, 1, "old", request, java.time.LocalDateTime.now()));
        var batch = new UserMemoryStore.Batch(7, "session-a", 0, "token", List.of(new UserMemoryStore.Turn(2, request,
                "我学过 Python，看过微服务。之前 Java 说错了，只学过几天 Java。我喜欢项目练习。", null)), existing, 1);

        var candidates = new UserMemoryExtractor(model).extract(batch);
        assertThat(candidates).hasSize(4);
        assertThat(candidates).extracting(UserMemoryStore.Candidate::targetId).containsExactly(null, null, "11", null);
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(captured.capture());
        var input = json.readTree(captured.getValue().getUserMessage().getText());
        assertThat(input.path("existing").size()).isEqualTo(2);
        assertThat(input.path("existing").get(0).path("id").asText()).isEqualTo("11");
        assertThat(input.path("existing").get(1).path("id").asText()).isEqualTo("12");
    }

    @Test
    void tooManyFactsAreRejected() throws Exception {
        ChatModel model = mock(ChatModel.class);
        var candidate = new UserMemoryStore.Candidate("background", "学过 Python", "request", "学过 Python");
        String result = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "memories", java.util.Collections.nCopies(UserMemoryStore.MAX_CANDIDATES + 1, candidate)));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(result))))));
        var batch = new UserMemoryStore.Batch(7, "session-a", 0, "token", List.of(), List.of(), 1);
        assertThatThrownBy(() -> new UserMemoryExtractor(model).extract(batch)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedResponseIsNotAccepted() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("not-json"))))));
        var batch = new UserMemoryStore.Batch(7, "session-a", 0, "token", List.of(), List.of(), 1);
        assertThatThrownBy(() -> new UserMemoryExtractor(model).extract(batch)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stalledModelStreamIsCancelledAtTheOverallDeadline() {
        ChatModel model = mock(ChatModel.class);
        var cancelled = new AtomicBoolean();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        var batch = new UserMemoryStore.Batch(7, "session-a", 0, "token", List.of(), List.of(), 1);
        assertThatThrownBy(() -> new UserMemoryExtractor(model, Duration.ofMillis(100)).extract(batch))
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(cancelled).isTrue();
    }

    @Test
    void continuouslyTricklingChunksCannotExtendTheOverallDeadline() {
        ChatModel model = mock(ChatModel.class);
        var cancelled = new AtomicBoolean();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.interval(Duration.ofMillis(10))
                .map(i -> new ChatResponse(List.of(new Generation(new AssistantMessage(" ")))))
                .doOnCancel(() -> cancelled.set(true)));
        var batch = new UserMemoryStore.Batch(7, "session-a", 0, "token", List.of(), List.of(), 1);
        assertThatThrownBy(() -> new UserMemoryExtractor(model, Duration.ofMillis(100)).extract(batch))
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(cancelled).isTrue();
    }

    @Test
    void toolCallsAndOversizedResultsAreRejected() {
        ChatModel model = mock(ChatModel.class);
        var tool = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "searchCourses", "{}"))).build();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(tool)))))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("x".repeat(8193)))))));
        var batch = new UserMemoryStore.Batch(7, "session-a", 0, "token", List.of(), List.of(), 1);
        var extractor = new UserMemoryExtractor(model);
        assertThatThrownBy(() -> extractor.extract(batch)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> extractor.extract(batch)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enablingRequiresMysqlValidKeyAndNoContentObservation() {
        var config = new UserMemoryConfig();
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        assertThat(config.userMemoryCipher(key, "mysql", false, false, new com.tianji.aigc.config.UserMemoryKeyProperties())).isNotNull();
        assertThatThrownBy(() -> config.userMemoryCipher(key, "mongodb", false, false, new com.tianji.aigc.config.UserMemoryKeyProperties())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.userMemoryCipher(key, "mysql", true, false, new com.tianji.aigc.config.UserMemoryKeyProperties())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.userMemoryCipher(key, "mysql", false, true, new com.tianji.aigc.config.UserMemoryKeyProperties())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.userMemoryCipher("", "mysql", false, false, new com.tianji.aigc.config.UserMemoryKeyProperties())).isInstanceOf(IllegalArgumentException.class);
        var cipher = new UserMemoryCipher(key);
        String encrypted = cipher.encrypt(7, "只属于用户 7");
        assertThat(cipher.decrypt(7, encrypted)).isEqualTo("只属于用户 7");
        assertThatThrownBy(() -> cipher.decrypt(8, encrypted)).isInstanceOf(IllegalStateException.class);
    }
}
