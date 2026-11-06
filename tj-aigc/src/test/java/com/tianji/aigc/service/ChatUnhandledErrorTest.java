package com.tianji.aigc.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tianji.aigc.ChatTestSupport;
import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.mcp.McpCallScope;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatUnhandledErrorTest {
    private static final String SESSION = "unhandled-error";
    private static final String PRIVATE_ERROR = "provider body /v1/chat sk-test-private";
    private final ChatModel model = mock(ChatModel.class);
    private final ChatGenerationStore store = mock(ChatGenerationStore.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
    private final AtomicBoolean occupied = new AtomicBoolean();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ChatServiceImpl.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUser(42L);
        logs.start();
        logger.addAppender(logs);
        var generation = mock(ChatGenerationStore.Generation.class);
        when(generation.isValid()).thenReturn(true);
        when(generation.requestId()).thenReturn("req-unhandled-error");
        when(store.acquire(any())).thenAnswer(call -> { occupied.set(true); return generation; });
        when(store.renew(any())).thenReturn(true);
        when(store.isCurrent(any())).thenReturn(true);
        doAnswer(call -> { occupied.set(false); return null; }).when(store).release(any());
        var client = ChatClient.builder(model)
                .defaultAdvisors(new SpringAIConfig().messageChatMemoryAdvisor(memory)).build();
        var prompts = mock(SystemPromptConfig.class);
        when(prompts.getChatSystemMessage()).thenReturn(new AtomicReference<>("system"));
        service = ChatTestSupport.ordinaryChat(client, prompts, store, memory);
    }

    @AfterEach
    void cleanUp() {
        logger.detachAppender(logs);
        logs.stop();
        UserContext.removeUser();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelFailureBeforeOrAfterFirstTokenEmitsSafeErrorThenStop(boolean partial) {
        when(model.stream(any(Prompt.class))).thenReturn(stream(partial,
                new IllegalStateException(PRIVATE_ERROR, new RuntimeException("credential-body"))));

        var events = service.chat("private question", SESSION).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(ChatEventVO::getEventType)
                .containsExactlyElementsOf(partial ? List.of(1001, 1005, 1002) : List.of(1005, 1002));
        assertThat(events.get(partial ? 1 : 0).getEventData()).isEqualTo(Map.of(
                "code", "CHAT_GENERATION_FAILED", "message", "回答生成失败，请稍后重试。"));
        assertThat(memory.get("42_" + SESSION)).extracting(Message::getText).containsExactly("private question");
        assertThat(logs.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_ERROR, "private question", "private answer", "credential-body");
            assertThat(event.getThrowableProxy()).isNull();
        });
        verify(store).release(any());
        assertThat(occupied).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mcpStopBeforeOrAfterFirstTokenDoesNotReportGenerationFailure(boolean partial) {
        when(model.stream(any(Prompt.class))).thenReturn(stream(partial,
                new IllegalStateException(PRIVATE_ERROR, new McpCallScope.CancelledException())));

        var events = service.chat("question", SESSION).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(ChatEventVO::getEventType)
                .containsExactlyElementsOf(partial ? List.of(1001, 1002) : List.of(1002));
        verify(store).release(any());
        assertThat(occupied).isFalse();
    }

    @Test
    void courseToolBudgetExhaustionUsesGenericErrorAndReleasesGeneration() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(
                new com.tianji.aigc.tools.CourseToolCallLimitException()));

        var events = service.chat("question", SESSION).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData()).isEqualTo(Map.of(
                "code", "CHAT_GENERATION_FAILED", "message", "回答生成失败，请稍后重试。"));
        verify(store).release(any());
        assertThat(occupied).isFalse();
    }

    @ParameterizedTest
    @MethodSource("classifiedFailures")
    void dedicatedErrorsKeepTheirCodes(Throwable error, String code) {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(error));

        var events = service.chat("question", SESSION).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) events.get(0).getEventData()).get("code")).isEqualTo(code);
        assertThat(events.get(0).getEventData().toString()).doesNotContain(PRIVATE_ERROR, "CHAT_GENERATION_FAILED");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        verify(store).release(any());
        assertThat(occupied).isFalse();
    }

    private static Stream<Arguments> classifiedFailures() throws Exception {
        var mcp = Stream.of(com.tianji.aigc.mcp.McpToolException.Kind.values())
                .map(kind -> Arguments.of(new IllegalStateException(PRIVATE_ERROR,
                        new com.tianji.aigc.mcp.McpToolException(kind)), "MCP_TOOL_" + kind.name()));
        return Stream.concat(mcp, Stream.of(
                Arguments.of(new IllegalStateException(PRIVATE_ERROR,
                        new com.tianji.aigc.knowledge.KnowledgeContextSelector.ContextTooLongException(PRIVATE_ERROR)),
                        "KNOWLEDGE_CONTEXT_TOO_LONG"),
                Arguments.of(new com.tianji.aigc.knowledge.KnowledgeContextSelector.ContextTooLongException(PRIVATE_ERROR),
                        "KNOWLEDGE_CONTEXT_TOO_LONG"),
                Arguments.of(new com.tianji.aigc.knowledge.course.CourseSemanticSearch.RetrievalException(
                        new RuntimeException(PRIVATE_ERROR)), "COURSE_RETRIEVAL_FAILED"),
                Arguments.of(marker("agent.AgentDispatcher$RoutingException", true), "AGENT_ROUTING_FAILED"),
                Arguments.of(marker("tools.CourseToolPreparationAdvisor$ToolRoundLimitException", false), "COURSE_PREPARATION_INCOMPLETE"),
                Arguments.of(new com.tianji.aigc.tools.PublicRuleCallLimitException(), "PUBLIC_RULE_CALL_LIMIT"),
                Arguments.of(marker("knowledge.KnowledgeQueryRewriter$RewriteException", true), "KNOWLEDGE_QUERY_REWRITE_FAILED"),
                Arguments.of(marker("knowledge.KnowledgeRetrievalAdvisor$RetrievalException", true), "KNOWLEDGE_RETRIEVAL_FAILED")));
    }

    // 只构造实际分类标记；业务触发路径由原有工作流测试覆盖。
    private static Throwable marker(String nestedClass, boolean cause) throws Exception {
        var type = Class.forName("com.tianji.aigc." + nestedClass);
        var constructor = cause ? type.getDeclaredConstructor(Throwable.class) : type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (Throwable) (cause ? constructor.newInstance(new RuntimeException(PRIVATE_ERROR)) : constructor.newInstance());
    }

    private Flux<ChatResponse> stream(boolean partial, Throwable error) {
        var first = new ChatResponse(List.of(new Generation(new AssistantMessage("private answer"))));
        return (partial ? Flux.just(first) : Flux.<ChatResponse>empty()).concatWith(Flux.error(error));
    }
}
