package com.tianji.aigc.tools;

import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用真实 OpenAiChatModel + mock OpenAiApi 证明：单响应多 tool_calls 超预算会硬中断内部工具循环。
 */
class CourseToolCallBudgetOpenAiChatModelTest {
    private static final int BUDGET = 3;

    private final CourseClient courses = mock(CourseClient.class);
    private final SearchClient search = mock(SearchClient.class);
    private final AtomicInteger modelRequests = new AtomicInteger();
    private CourseTools tools;

    @BeforeEach
    void setUp() {
        when(search.queryCoursesIdByName(any())).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(anyList())).thenReturn(List.of(simple(1L, "Java课")));
        tools = new CourseTools(courses, search, null, (id, scope) -> null, BUDGET);
    }

    @Test
    void openAiChatModelStopsWhenOneResponseEmitsMoreToolCallsThanBudget() {
        OpenAiApi api = mock(OpenAiApi.class);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any(MultiValueMap.class)))
                .thenAnswer(invocation -> {
                    modelRequests.incrementAndGet();
                    return ResponseEntity.ok(multiToolCallCompletion(BUDGET + 2));
                });

        var manager = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new SpringAIConfig().toolExecutionExceptionProcessor(false))
                .build();
        var model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("mock-model").temperature(0.0).build())
                .toolCallingManager(manager)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();

        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations());
        toolContext.put(CourseTools.TURN_CALLS_KEY, new AtomicInteger(0));

        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(tools))
                .toolContext(toolContext)
                .internalToolExecutionEnabled(true)
                .build();

        assertThatThrownBy(() -> model.call(new Prompt(List.of(new UserMessage("搜 Java 课")), options)))
                .satisfies(error -> assertThat(CourseToolCallLimitException.is(error)).isTrue());

        assertThat(modelRequests.get()).isEqualTo(1);
        verify(search, times(BUDGET)).queryCoursesIdByName(any());
    }

    @Test
    void preparationCreatesAFreshBudgetAndInterruptsMultipleCallsOnEachTurn() {
        var model = mock(ChatModel.class);
        var chain = mock(StreamAdvisorChain.class);
        var calls = new ArrayList<AssistantMessage.ToolCall>();
        for (int i = 0; i < BUDGET + 2; i++) {
            calls.add(new AssistantMessage.ToolCall("call-" + i, "function", "searchCourses",
                    "{\"keyword\":\"Java" + i + "\"}"));
        }
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(calls).build()))));
        var advisor = new CourseToolPreparationAdvisor(model);

        for (int turn = 0; turn < 2; turn++) {
            var options = ToolCallingChatOptions.builder()
                    .toolCallbacks(ToolCallbacks.from(tools))
                    .toolContext(Map.of(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations(),
                            CourseToolPreparationAdvisor.AGENT_TYPE, AgentTypeEnum.CONSULT))
                    .build();
            var request = new ChatClientRequest(new Prompt(List.of(new UserMessage("搜 Java 课")), options), Map.of());

            assertThatThrownBy(() -> advisor.adviseStream(request, chain).collectList().block(Duration.ofSeconds(5)))
                    .satisfies(error -> assertThat(CourseToolCallLimitException.is(error)).isTrue());
        }

        verify(search, times(BUDGET * 2)).queryCoursesIdByName(any());
        verify(model, times(2)).call(any(Prompt.class));
        verify(chain, never()).nextStream(any());
    }

    @Test
    void defaultProcessorWouldSwallowLimitAndContinue() {
        OpenAiApi api = mock(OpenAiApi.class);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any(MultiValueMap.class)))
                .thenAnswer(invocation -> {
                    int n = modelRequests.incrementAndGet();
                    if (n > 4) {
                        return ResponseEntity.ok(textCompletion("stopped after proving swallow"));
                    }
                    return ResponseEntity.ok(multiToolCallCompletion(1));
                });

        var model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("mock-model").temperature(0.0).build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();

        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations());
        toolContext.put(CourseTools.TURN_CALLS_KEY, new AtomicInteger(0));

        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(tools))
                .toolContext(toolContext)
                .internalToolExecutionEnabled(true)
                .build();

        model.call(new Prompt(List.of(new UserMessage("搜 Java 课")), options));
        assertThat(modelRequests.get()).isGreaterThan(BUDGET);
    }

    private static OpenAiApi.ChatCompletion multiToolCallCompletion(int count) {
        var calls = new ArrayList<OpenAiApi.ChatCompletionMessage.ToolCall>();
        for (int i = 1; i <= count; i++) {
            var function = new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                    "searchCourses", "{\"keyword\":\"Java" + i + "\"}");
            calls.add(new OpenAiApi.ChatCompletionMessage.ToolCall("call-" + i, "function", function));
        }
        var message = new OpenAiApi.ChatCompletionMessage(
                "", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null,
                calls, null, null, null, null);
        var choice = new OpenAiApi.ChatCompletion.Choice(
                OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS, 0, message, null);
        return new OpenAiApi.ChatCompletion(
                "cmpl-multi", List.of(choice), 1L, "mock-model", null, null, "chat.completion", null);
    }

    private static OpenAiApi.ChatCompletion textCompletion(String text) {
        var message = new OpenAiApi.ChatCompletionMessage(text, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
        var choice = new OpenAiApi.ChatCompletion.Choice(
                OpenAiApi.ChatCompletionFinishReason.STOP, 0, message, null);
        return new OpenAiApi.ChatCompletion(
                "cmpl-final", List.of(choice), 1L, "mock-model", null, null, "chat.completion", null);
    }

    private static CourseSimpleInfoDTO simple(Long id, String name) {
        var value = new CourseSimpleInfoDTO();
        value.setId(id);
        value.setName(name);
        value.setStatus(2);
        value.setPrice(100);
        value.setFree(false);
        return value;
    }
}
