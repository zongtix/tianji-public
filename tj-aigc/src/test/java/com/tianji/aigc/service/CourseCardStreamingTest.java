package com.tianji.aigc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.aigc.service.impl.ChatSessionServiceImpl;
import com.tianji.aigc.tools.CourseRecommendations;
import com.tianji.aigc.tools.result.CourseCard;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CourseCardStreamingTest {
    private final ChatModel model = mock(ChatModel.class);
    private final ChatGenerationStore store = mock(ChatGenerationStore.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
    private final AtomicBoolean current = new AtomicBoolean(true);
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUser(42L);
        var generation = mock(ChatGenerationStore.Generation.class);
        when(generation.isValid()).thenReturn(true);
        when(store.acquire(any())).thenReturn(generation);
        when(store.renew(any())).thenAnswer(invocation -> current.get());
        when(store.isCurrent(any())).thenAnswer(invocation -> current.get());
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var client = ChatClient.builder(model)
                .defaultAdvisors(new SpringAIConfig().messageChatMemoryAdvisor(memory)).build();
        var prompts = mock(SystemPromptConfig.class);
        when(prompts.getChatSystemMessage()).thenReturn(new AtomicReference<>("原有系统提示词 {now}"));
        var agent = new com.tianji.aigc.agent.RecommendAgent(client, prompts,
                mock(com.tianji.aigc.tools.CourseTools.class));
        service = com.tianji.aigc.ChatTestSupport.withAgent(agent, prompts, store, memory);
    }

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"searchCourses", "queryCourseById"})
    void exhaustedPreparationEmitsPublicErrorAndStopWithoutAnswerOrCards(String toolName) {
        var courses = mock(com.tianji.api.client.course.CourseClient.class);
        var search = mock(com.tianji.api.client.search.SearchClient.class);
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of());
        var detail = new com.tianji.api.dto.course.CourseBaseInfoDTO();
        detail.setId(42L); detail.setName("Java课程"); detail.setPrice(19900);
        when(courses.getCourseBaseInfoById(42L)).thenReturn(detail);
        var simple = new com.tianji.api.dto.course.CourseSimpleInfoDTO();
        simple.setId(42L); simple.setName("Java课程"); simple.setStatus(2); simple.setPrice(19900);
        when(courses.getSimpleInfoList(List.of(42L))).thenReturn(List.of(simple));
        String arguments = toolName.equals("searchCourses") ? "{\"keyword\":\"Java\"}" : "{\"courseId\":42}";
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("内部准备文字")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("search-call", "function", toolName,
                                arguments))).build()))));
        var config = new SpringAIConfig();
        var client = config.chatClient(ChatClient.builder(model), config.loggerAdvisor(),
                config.messageChatMemoryAdvisor(memory), model);
        var prompts = mock(SystemPromptConfig.class);
        when(prompts.getChatSystemMessage()).thenReturn(new AtomicReference<>("system"));
        service = com.tianji.aigc.ChatTestSupport.withAgent(
                new com.tianji.aigc.agent.RecommendAgent(client, prompts,
                        new com.tianji.aigc.tools.CourseTools(courses, search)), prompts, store, memory);

        var events = service.chat("推荐Java课程", "exhausted").collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString()).contains("COURSE_PREPARATION_INCOMPLETE")
                .doesNotContain("内部准备文字", "Exception", "search-call");
        if (toolName.equals("searchCourses")) verify(search, times(8)).queryCoursesIdByName("Java");
        else verify(courses, times(8)).getCourseBaseInfoById(42L);
        verify(model, never()).stream(any(Prompt.class));
        verify(store).release(any());
        assertThat(memory.get("42_exhausted")).extracting(message -> message.getText()).containsExactly("推荐Java课程");
    }

    @Test
    void detailQueryEmitsCardAndPersistsItWithoutRecommendation() {
        var courses = mock(com.tianji.api.client.course.CourseClient.class);
        var tools = new com.tianji.aigc.tools.CourseTools(courses,
                mock(com.tianji.api.client.search.SearchClient.class));
        var detail = new com.tianji.api.dto.course.CourseBaseInfoDTO();
        detail.setId(1589905661084430337L);
        detail.setName("Java 入门");
        detail.setPrice(1999);
        when(courses.getCourseBaseInfoById(detail.getId())).thenReturn(detail);
        var simple = new com.tianji.api.dto.course.CourseSimpleInfoDTO();
        simple.setId(detail.getId());
        simple.setName(detail.getName());
        simple.setPrice(detail.getPrice());
        simple.setStatus(2);
        when(courses.getSimpleInfoList(List.of(detail.getId()))).thenReturn(List.of(simple));
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            var context = new org.springframework.ai.chat.model.ToolContext(
                    java.util.Map.of(CourseRecommendations.CONTEXT_KEY, results(invocation.getArgument(0))));
            tools.queryCourseById(detail.getId(), context);
            return Flux.just(chunk("课程信息"));
        });

        var events = service.chat("查询id为1589905661084430337的课程", "detail-card")
                .collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1004, 1002);
        var mapper = new ObjectMapper();
        var card = mapper.valueToTree(events.get(1).getEventData()).path("cards").get(0);
        assertThat(card.path("courseId").asText()).isEqualTo("1589905661084430337");
        assertThat(card.path("reason").isNull()).isTrue();
        var saved = memory.get("42_detail-card").get(1);
        assertThat(saved.getText()).isEqualTo("课程信息");
        assertThat(saved.getMetadata()).containsKey(MessageUtil.COURSE_CARDS);
    }

    @Test
    void completionEmitsCardsBeforeTerminalAndPersistsThemWithAnswer() {
        AtomicReference<CourseRecommendations> captured = new AtomicReference<>();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertThat(prompt.getSystemMessage().getText()).contains("原有系统提示词", "recommendCourses");
            var results = results(prompt);
            select(results, "9007199254740993");
            captured.set(results);
            return Flux.just(chunk("推荐这门课"));
        });

        var events = service.chat("推荐课程", "cards").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1004, 1002);
        var messages = memory.get("42_cards");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getText()).isEqualTo("推荐这门课");
        var restored = MessageUtil.toMessage(MessageUtil.toJson(messages.get(1)));
        var json = new ObjectMapper().valueToTree(restored.getMetadata().get(MessageUtil.COURSE_CARDS));
        assertThat(json.get(0).get("courseId").asText()).isEqualTo("9007199254740993");
        var historyService = spy(new ChatSessionServiceImpl(new SessionProperties(), memory, new com.tianji.aigc.knowledge.KnowledgeBaseAccess(), mock(com.tianji.aigc.service.ChatGenerationStore.class), mock(org.springframework.transaction.PlatformTransactionManager.class)));
        doReturn(com.tianji.aigc.entity.ChatSession.builder().userId(42L).build())
                .when(historyService).requireOwnedSession("cards");
        var history = historyService.queryBySessionId("cards");
        assertThat(history.get(1).getParams()).containsKey(MessageUtil.COURSE_CARDS);
        assertThat(captured.get().contains("9007199254740993")).isFalse();
        verify(store).release(any());
    }

    @Test
    void cardsOnlyAnswerIsStillSavedAndSent() {
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            select(results(invocation.getArgument(0)), "1");
            return Flux.empty();
        });
        var events = service.chat("推荐", "only-cards").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1004, 1002);
        assertThat(memory.get("42_only-cards").get(1).getMetadata()).containsKey(MessageUtil.COURSE_CARDS);
    }

    @Test
    void stoppedGenerationDoesNotEmitOrPersistUnsentCards() throws Exception {
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            select(results(invocation.getArgument(0)), "1");
            return source.asFlux();
        });
        var future = service.chat("推荐", "stopped-cards").collectList().toFuture();
        await().atMost(Duration.ofSeconds(3)).until(() -> source.currentSubscriberCount() == 1);
        source.tryEmitNext(chunk("部分回答")).orThrow();
        current.set(false);
        // 停止标记与请求归属分别模拟：仍拥有本次租约，允许补存文字。
        when(store.isCurrent(any())).thenReturn(true);
        assertThat(future.get(5, TimeUnit.SECONDS)).extracting(ChatEventVO::getEventType)
                .containsExactly(1001, 1002);
        var saved = memory.get("42_stopped-cards").get(1);
        assertThat(MessageUtil.isStopped(saved)).isTrue();
        assertThat(saved.getMetadata()).doesNotContainKey(MessageUtil.COURSE_CARDS);
    }

    @Test
    void failedGenerationDoesNotSaveRecommendationMetadata() {
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            select(results(invocation.getArgument(0)), "1");
            return Flux.concat(Flux.just(chunk("partial")), Flux.error(new IllegalStateException("model failed")));
        });
        var events = service.chat("推荐", "failed-cards").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1005, 1002);
        assertThat(events.get(1).getEventData().toString()).contains("CHAT_GENERATION_FAILED");
        assertThat(memory.get("42_failed-cards")).hasSize(1);
        verify(store).release(any());
    }

    @Test
    void lostOwnershipSuppressesCardsAndCardMetadata() {
        when(store.isCurrent(any())).thenReturn(false);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            select(results(invocation.getArgument(0)), "1");
            return Flux.just(chunk("回答"));
        });
        var events = service.chat("推荐", "old-cards").collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
        assertThat(memory.get("42_old-cards").get(1).getMetadata()).doesNotContainKey(MessageUtil.COURSE_CARDS);
    }

    @Test
    void overlappingRequestsKeepSeparateRecommendationContainers() throws Exception {
        Sinks.Many<ChatResponse> first = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<ChatResponse> second = Sinks.many().unicast().onBackpressureBuffer();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            boolean isFirst = prompt.getUserMessage().getText().equals("first");
            select(results(prompt), isFirst ? "1" : "2");
            return isFirst ? first.asFlux() : second.asFlux();
        });
        var a = service.chat("first", "first-cards").collectList().toFuture();
        var b = service.chat("second", "second-cards").collectList().toFuture();
        await().atMost(Duration.ofSeconds(3)).until(() -> first.currentSubscriberCount() == 1 && second.currentSubscriberCount() == 1);
        second.tryEmitNext(chunk("B")).orThrow();
        second.tryEmitComplete().orThrow();
        first.tryEmitNext(chunk("A")).orThrow();
        first.tryEmitComplete().orThrow();
        var mapper = new ObjectMapper();
        assertThat(mapper.valueToTree(a.get(5, TimeUnit.SECONDS).get(1).getEventData()).path("cards").get(0).path("courseId").asText()).isEqualTo("1");
        assertThat(mapper.valueToTree(b.get(5, TimeUnit.SECONDS).get(1).getEventData()).path("cards").get(0).path("courseId").asText()).isEqualTo("2");
    }

    private CourseRecommendations results(Prompt prompt) {
        return (CourseRecommendations) ((ToolCallingChatOptions) prompt.getOptions())
                .getToolContext().get(CourseRecommendations.CONTEXT_KEY);
    }

    private void select(CourseRecommendations results, String id) {
        var cards = List.of(new CourseCard(id, "Java", "/java.jpg", 1999, 12, "适合初学者"));
        results.addCandidates(cards);
        results.select(cards);
    }

    private ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
