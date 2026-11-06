package com.tianji.aigc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.dto.aigc.BotReplyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BotTextServiceTest {
    private final ChatModel model = mock(ChatModel.class);
    private final BotTextService service;

    BotTextServiceTest() throws java.io.IOException {
        service = new BotTextService(model, new ObjectMapper(), new ClassPathResource("prompts/bot-system-message.txt"));
    }

    private BotReplyRequest request() {
        var request = new BotReplyRequest();
        request.setTitle("Spring 事务");
        request.setComment("为什么自调用失效？");
        return request;
    }

    @Test
    void reusesModelWithBoundedOutputNoToolsAndNoConversationMemory() throws Exception {
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getInstructions()).hasSize(2);
            var input = new ObjectMapper().readTree(prompt.getUserMessage().getText());
            assertThat(input.get("comment").asText()).isEqualTo("为什么自调用失效？");
            var options = (OpenAiChatOptions) prompt.getOptions();
            assertThat(options.getMaxTokens()).isEqualTo(768);
            assertThat(options.getToolChoice()).isEqualTo("none");
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            return Flux.just(chunk("因为没有经过"), chunk("代理。"));
        });
        assertThat(service.reply(request())).isEqualTo("因为没有经过代理。");
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test
    void emptyOrExcessiveResponseFailsInsteadOfBecomingPublishedComment() {
        for (String text : List.of(" ", "长".repeat(8001))) {
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(chunk(text)));
            assertThatThrownBy(() -> service.reply(request())).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void providerErrorsDoNotRetry() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("provider failed")));
        assertThatThrownBy(() -> service.reply(request())).isInstanceOf(IllegalStateException.class);
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test
    void toolCallIsRejected() {
        var output = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "buyCourse", "{}"))).build();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(output)))));
        assertThatThrownBy(() -> service.reply(request())).isInstanceOf(IllegalStateException.class);
    }

    private ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
