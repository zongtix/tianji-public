package com.tianji.aigc.tools;

import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.RagCandidate;
import com.tianji.aigc.knowledge.RagRequestGuard;
import com.tianji.aigc.mcp.McpCallScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MultiValueMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用真实 OpenAiChatModel + mock OpenAiApi 证明：回合预算耗尽会打断 internalCall 工具递归。
 */
class PublicRuleCallBudgetOpenAiChatModelTest {
    private static final int BUDGET = 3;

    private final KnowledgeRetrievalPipeline pipeline = mock(KnowledgeRetrievalPipeline.class);
    private final KnowledgeBaseAccess access = new KnowledgeBaseAccess();
    private final AtomicInteger modelRequests = new AtomicInteger();
    private PublicRuleTools tools;

    @BeforeEach
    void setUp() {
        access.setPublicBases(Map.of(
                "tianji-points", List.of("tianji-points-guide"),
                "tianji-coupons", List.of("tianji-coupon-guide")));
        tools = new PublicRuleTools(pipeline, access, BUDGET, 3);
        when(pipeline.retrieve(anyString(), any(), any(RagRequestGuard.class)))
                .thenReturn(new KnowledgeRetrievalPipeline.Result(
                        List.of(candidate("tianji-points-guide", "签到", 0, "h", "每日签到得积分")),
                        List.of(), "not_executed"));
    }

    @Test
    void openAiChatModelStopsWhenPublicRuleBudgetExhausted() {
        OpenAiApi api = mock(OpenAiApi.class);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any(MultiValueMap.class)))
                .thenAnswer(invocation -> {
                    int n = modelRequests.incrementAndGet();
                    return ResponseEntity.ok(toolCallCompletion(n));
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
        toolContext.put(McpCallScope.CONTEXT_KEY, new McpCallScope(() -> true, () -> true));
        toolContext.put(PublicRuleTools.USER_ID_KEY, 1L);
        toolContext.put(PublicRuleTools.TURN_CALLS_KEY, new AtomicInteger(0));
        toolContext.put(PublicRuleTools.DOMAIN_CALLS_KEY, ConcurrentHashMap.newKeySet());

        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(tools))
                .toolContext(toolContext)
                .internalToolExecutionEnabled(true)
                .build();

        assertThatThrownBy(() -> model.call(new Prompt(List.of(new UserMessage("积分规则？")), options)))
                .satisfies(error -> assertThat(PublicRuleCallLimitException.is(error)).isTrue());

        // 预算 3：第 4 次工具执行抛出；模型请求次数有界（远小于无上限时的 13）
        assertThat(modelRequests.get()).isBetween(BUDGET + 1, BUDGET + 2);
        verify(pipeline, times(1)).retrieve(anyString(), any(), any());
    }

    @Test
    void defaultProcessorWouldSwallowLimitAndAllowUnboundedModelRequests() {
        OpenAiApi api = mock(OpenAiApi.class);
        when(api.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any(MultiValueMap.class)))
                .thenAnswer(invocation -> {
                    int n = modelRequests.incrementAndGet();
                    if (n > 12) {
                        return ResponseEntity.ok(textCompletion("stop after proving unbounded"));
                    }
                    return ResponseEntity.ok(toolCallCompletion(n));
                });

        // 默认处理器不重抛 → 异常变工具字符串 → 模型可继续要工具
        var model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("mock-model").temperature(0.0).build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();

        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(McpCallScope.CONTEXT_KEY, new McpCallScope(() -> true, () -> true));
        toolContext.put(PublicRuleTools.USER_ID_KEY, 1L);
        toolContext.put(PublicRuleTools.TURN_CALLS_KEY, new AtomicInteger(0));
        toolContext.put(PublicRuleTools.DOMAIN_CALLS_KEY, ConcurrentHashMap.newKeySet());

        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(tools))
                .toolContext(toolContext)
                .internalToolExecutionEnabled(true)
                .build();

        model.call(new Prompt(List.of(new UserMessage("积分规则？")), options));

        assertThat(modelRequests.get()).isGreaterThan(BUDGET + 2);
    }

    @ParameterizedTest
    @CsvSource(value = {"POINTS|签到积分", "UNKNOWN|签到积分", "''|签到积分", "POINTS|''"}, delimiter = '|')
    void streamingAutoConfiguredManagerStopsAllRepeatedAttempts(String domain, String question) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .withBean(ToolExecutionExceptionProcessor.class,
                        () -> new SpringAIConfig().toolExecutionExceptionProcessor(false))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiApi api = mock(OpenAiApi.class);
                    String arguments = new ObjectMapper().writeValueAsString(
                            Map.of("domain", domain, "question", question));
                    when(api.chatCompletionStream(any(OpenAiApi.ChatCompletionRequest.class), any(MultiValueMap.class)))
                            .thenAnswer(invocation -> Flux.defer(() -> {
                                int n = modelRequests.incrementAndGet();
                                // 对失效的上限给出有限哨兵，避免回归失败时测试自身无限递归。
                                if (n > BUDGET + 2) return Flux.error(new IllegalStateException("loop exceeded budget"));
                                var function = new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                        "searchPublicRules", arguments);
                                var call = new OpenAiApi.ChatCompletionMessage.ToolCall("call-" + n, "function", function);
                                var message = new OpenAiApi.ChatCompletionMessage("",
                                        OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null,
                                        List.of(call), null, null, null, null);
                                var choice = new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                                        OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS, 0, message, null);
                                return Flux.just(new OpenAiApi.ChatCompletionChunk("chunk-" + n, List.of(choice),
                                        1L, "mock-model", null, null, "chat.completion.chunk", null));
                            }));
                    var model = OpenAiChatModel.builder().openAiApi(api)
                            .defaultOptions(OpenAiChatOptions.builder().model("mock-model").build())
                            .toolCallingManager(context.getBean(ToolCallingManager.class))
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
                    var options = ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(tools))
                            .toolContext(Map.of(
                                    McpCallScope.CONTEXT_KEY, new McpCallScope(() -> true, () -> true),
                                    PublicRuleTools.USER_ID_KEY, 1L,
                                    PublicRuleTools.TURN_CALLS_KEY, new AtomicInteger(),
                                    PublicRuleTools.DOMAIN_CALLS_KEY, ConcurrentHashMap.newKeySet()))
                            .internalToolExecutionEnabled(true).build();
                    assertThatThrownBy(() -> model.stream(new Prompt(List.of(new UserMessage("规则")), options))
                            .collectList().block(Duration.ofSeconds(5)))
                            .satisfies(error -> assertThat(PublicRuleCallLimitException.is(error)).isTrue());
                    assertThat(modelRequests.get()).isEqualTo(BUDGET + 1);
                    verify(pipeline, times("POINTS".equals(domain) && !question.isBlank() ? 1 : 0))
                            .retrieve(anyString(), any(), any());
                });
    }

    @Test
    void processorPreservesConfiguredHandlingOfOrdinaryToolErrors() {
        var failure = new org.springframework.ai.tool.execution.ToolExecutionException(
                ToolCallbacks.from(tools)[0].getToolDefinition(), new IllegalArgumentException("tool failed"));
        assertThat(new SpringAIConfig().toolExecutionExceptionProcessor(false).process(failure))
                .isEqualTo("tool failed");
        assertThatThrownBy(() -> new SpringAIConfig().toolExecutionExceptionProcessor(true).process(failure))
                .isSameAs(failure);
    }

    private static OpenAiApi.ChatCompletion toolCallCompletion(int n) {
        var function = new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                "searchPublicRules",
                "{\"domain\":\"POINTS\",\"question\":\"签到积分" + n + "\"}");
        var toolCall = new OpenAiApi.ChatCompletionMessage.ToolCall(
                "call-" + n, "function", function);
        var message = new OpenAiApi.ChatCompletionMessage(
                "", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null,
                List.of(toolCall), null, null, null, null);
        var choice = new OpenAiApi.ChatCompletion.Choice(
                OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS, 0, message, null);
        return new OpenAiApi.ChatCompletion(
                "cmpl-" + n, List.of(choice), 1L, "mock-model", null, null, "chat.completion", null);
    }

    private static OpenAiApi.ChatCompletion textCompletion(String text) {
        var message = new OpenAiApi.ChatCompletionMessage(text, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
        var choice = new OpenAiApi.ChatCompletion.Choice(
                OpenAiApi.ChatCompletionFinishReason.STOP, 0, message, null);
        return new OpenAiApi.ChatCompletion(
                "cmpl-final", List.of(choice), 1L, "mock-model", null, null, "chat.completion", null);
    }

    private static RagCandidate candidate(String documentId, String chapter, int chunk, String hash, String text) {
        var meta = new HashMap<String, Object>();
        meta.put("documentId", documentId);
        meta.put("chapter", chapter);
        meta.put("chunkIndex", chunk);
        meta.put("contentHash", hash);
        return new RagCandidate(new Document("id-" + documentId + chunk, text, meta),
                documentId + ":" + hash + ":" + chunk, Map.of(), 1.0, null);
    }
}
