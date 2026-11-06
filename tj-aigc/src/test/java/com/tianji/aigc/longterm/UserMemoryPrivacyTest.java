package com.tianji.aigc.longterm;

import com.tianji.aigc.config.RagObservabilityConfig;
import com.tianji.aigc.config.SpringAIConfig;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserMemoryPrivacyTest {
    @Test
    void enabledMemoryOmitsTheFullPromptLoggerFromTheChatClient() {
        var count = new AtomicInteger();
        StreamAdvisor logger = new StreamAdvisor() {
            public String getName() { return "test-logger"; }
            public int getOrder() { return 0; }
            public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
                count.incrementAndGet(); return chain.nextStream(request);
            }
        };
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))))));
        ChatMemory memory = MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
        var config = new SpringAIConfig();
        ReflectionTestUtils.setField(config, "userMemoryEnabled", true);
        var client = config.chatClient(ChatClient.builder(model), logger, config.messageChatMemoryAdvisor(memory), model, ObservationRegistry.NOOP);
        client.prompt().system("PRIVATE_MEMORY_SENTINEL").user("question")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "7_session"))
                .stream().content().collectList().block(Duration.ofSeconds(5));
        assertThat(count).hasValue(0);
    }

    @Test
    void disabledContentCaptureDoesNotAddMemoryToLangfuseAttributes() {
        var context = org.springframework.ai.chat.observation.ChatModelObservationContext.builder()
                .prompt(new Prompt("PRIVATE_MEMORY_SENTINEL")).provider("test").build();
        context.setName("gen_ai.client.operation");
        context.setResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_OUTPUT_SENTINEL")))));
        new RagObservabilityConfig().ragTraceAttributes(false).map(context);
        assertThat(context.getHighCardinalityKeyValue("langfuse.observation.input")).isNull();
        assertThat(context.getHighCardinalityKeyValue("langfuse.observation.output")).isNull();
        assertThat(context.getHighCardinalityKeyValues().toString()).doesNotContain("PRIVATE_MEMORY_SENTINEL", "PRIVATE_OUTPUT_SENTINEL");
    }
}
