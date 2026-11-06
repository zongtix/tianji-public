package com.tianji.aigc.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeQueryRewriterTest {
    private final ChatModel model = mock(ChatModel.class);
    private final KnowledgeQueryRewriter rewriter = new KnowledgeQueryRewriter(model);

    @Test
    void noHistorySkipsModelIncludingSystemMessages() {
        var result = rewriter.rewrite(request(new SystemMessage("系统要求"), new UserMessage("签到积分")))
                .block(Duration.ofSeconds(3));
        assertThat(result.query()).isEqualTo("签到积分");
        assertThat(result.clarification()).isFalse();
        verifyNoInteractions(model);
    }

    @Test
    void repeatedQuestionIsExcludedOnlyAtCurrentPosition() {
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            JsonNode input = input(invocation.getArgument(0));
            assertThat(input.path("history")).hasSize(2);
            assertThat(input.path("history").get(0).path("text").asText()).isEqualTo("第七天呢？");
            assertThat(input.path("currentQuestion").asText()).isEqualTo("第七天呢？");
            return reply("{\"action\":\"KEEP\",\"query\":\"\"}");
        });
        assertThat(rewriter.rewrite(request(new UserMessage("第七天呢？"), new AssistantMessage("请补充主题"),
                new UserMessage("第七天呢？"))).block(Duration.ofSeconds(3)).query()).isEqualTo("第七天呢？");
    }

    @Test
    void boundsHistoryCountAndKeepsMostRecentMessagesInOrder() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) messages.add(new UserMessage("历史" + i));
        messages.add(new SystemMessage("不应传入改写的系统内容"));
        messages.add(new UserMessage("本轮问题"));
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            JsonNode input = input(invocation.getArgument(0));
            assertThat(input.path("history")).hasSize(KnowledgeQueryRewriter.MAX_HISTORY_MESSAGES);
            assertThat(input.path("history").get(0).path("text").asText()).isEqualTo("历史4");
            assertThat(input.path("history").get(5).path("text").asText()).isEqualTo("历史9");
            assertThat(input.path("historyTruncated").asBoolean()).isTrue();
            assertThat(input.toString()).doesNotContain("不应传入改写的系统内容");
            return reply("{\"action\":\"KEEP\",\"query\":\"\"}");
        });
        rewriter.rewrite(request(messages.toArray(Message[]::new))).block(Duration.ofSeconds(3));
        verify(model).stream(any(Prompt.class));
    }

    @Test
    void boundsTotalHistoryCharactersAndMarksTruncation() {
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            JsonNode input = input(invocation.getArgument(0));
            int length = 0;
            for (JsonNode message : input.path("history")) length += message.path("text").asText().length();
            assertThat(length).isEqualTo(KnowledgeQueryRewriter.MAX_HISTORY_CHARS);
            assertThat(input.path("historyTruncated").asBoolean()).isTrue();
            assertThat(input.path("currentQuestion").asText()).isEqualTo("本轮问题");
            return reply("{\"action\":\"CLARIFY\",\"query\":\"\"}");
        });
        var result = rewriter.rewrite(request(new UserMessage("旧话题"), new AssistantMessage("字".repeat(6000)),
                new UserMessage("本轮问题"))).block(Duration.ofSeconds(3));
        assertThat(result.clarification()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "自由回答：11分", "{}", "null", "{\"action\":\"OTHER\"}",
            "{\"action\":\"REWRITE\",\"query\":\" \"}", "{\"action\":\"KEEP\"} trailing"})
    void invalidOutputFailsInsteadOfFallingBack(String output) {
        when(model.stream(any(Prompt.class))).thenReturn(reply(output));
        assertThatThrownBy(() -> rewriter.rewrite(followup()).block(Duration.ofSeconds(3)))
                .matches(KnowledgeQueryRewriter::isRewriteFailure);
    }

    @Test
    void streamedJsonIsCollectedBeforeParsing() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(reply("{\"action\":\"REWRITE\","),
                reply("\"query\":\"连续签到七天积分\"}")));
        assertThat(rewriter.rewrite(followup()).block(Duration.ofSeconds(3)).query()).isEqualTo("连续签到七天积分");
    }

    @Test
    void oversizedOutputCancelsRewriteWithoutFallback() {
        when(model.stream(any(Prompt.class))).thenReturn(reply("x".repeat(3001)));
        assertThatThrownBy(() -> rewriter.rewrite(followup()).block(Duration.ofSeconds(3)))
                .matches(KnowledgeQueryRewriter::isRewriteFailure);
    }

    @Test
    void timeoutCancelsModelStreamAndRemainsRewriteFailure() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        var shortTimeout = new KnowledgeQueryRewriter(model, Duration.ofMillis(50));
        assertThatThrownBy(() -> shortTimeout.rewrite(followup()).block(Duration.ofSeconds(3)))
                .matches(KnowledgeQueryRewriter::isRewriteFailure);
        assertThat(cancelled).isTrue();
    }

    @Test
    void toolCallOutputIsRejectedInsteadOfBeingExecutedOrUsedForRetrieval() {
        var output = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "searchCourses", "{}"))).build();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(output)))));
        assertThatThrownBy(() -> rewriter.rewrite(followup()).block(Duration.ofSeconds(3)))
                .matches(KnowledgeQueryRewriter::isRewriteFailure);
        verify(model).stream(any(Prompt.class));
        verifyNoMoreInteractions(model);
    }

    private ChatClientRequest followup() {
        return request(new UserMessage("签到积分"), new UserMessage("那连续七天呢？"));
    }

    private ChatClientRequest request(Message... messages) {
        return new ChatClientRequest(new Prompt(List.of(messages)), Map.of());
    }

    private JsonNode input(Prompt prompt) throws Exception {
        return new ObjectMapper().readTree(prompt.getUserMessage().getText());
    }

    private Flux<ChatResponse> reply(String text) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
