package com.tianji.aigc.tools;

import com.tianji.aigc.enums.AgentTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseToolPreparationAdvisorTest {
    @Test
    void knowledgeAgentBypassesPreparationEvenWithToolCallbacksAndCourseRecommendations() {
        var model = mock(ChatModel.class);
        var chain = mock(StreamAdvisorChain.class);
        var downstream = Flux.just(ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("knowledge answer")))))
                .build());
        when(chain.nextStream(any())).thenReturn(downstream);

        var recommendations = new CourseRecommendations();
        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(stubCallback("searchPublicRules")))
                .toolContext(Map.of(
                        CourseRecommendations.CONTEXT_KEY, recommendations,
                        CourseToolPreparationAdvisor.AGENT_TYPE, AgentTypeEnum.KNOWLEDGE))
                .build();
        var request = new ChatClientRequest(new Prompt(List.of(new UserMessage("公共规则")), options), Map.of());

        var result = new CourseToolPreparationAdvisor(model).adviseStream(request, chain).collectList().block();

        verify(chain).nextStream(same(request));
        verify(model, never()).call(any(Prompt.class));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).chatResponse().getResult().getOutput().getText()).isEqualTo("knowledge answer");
    }

    private static ToolCallback stubCallback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String input) {
                return "{}";
            }
        };
    }
}
