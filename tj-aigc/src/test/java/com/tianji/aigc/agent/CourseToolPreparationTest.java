package com.tianji.aigc.agent;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.tianji.aigc.config.AIProperties;
import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.tools.CourseRecommendations;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.aigc.mcp.McpCallScope;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class CourseToolPreparationTest {
    private final ChatModel model = mock(ChatModel.class);
    private final CourseClient courses = mock(CourseClient.class);
    private final SearchClient search = mock(SearchClient.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
    private ChatClient client;
    private SystemPromptConfig prompts;
    private final CourseRecommendations recommendations = new CourseRecommendations();

    @BeforeEach
    void setup() {
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var config = new SpringAIConfig();
        client = config.chatClient(ChatClient.builder(model), config.loggerAdvisor(),
                config.messageChatMemoryAdvisor(memory), model);
        prompts = new SystemPromptConfig(mock(NacosConfigManager.class), new AIProperties());
        prompts.init();
        var detail = new CourseBaseInfoDTO();
        detail.setId(42L); detail.setName("微服务课"); detail.setPrice(19900);
        detail.setCatalogue(List.of("认识微服务", "认识微服务 / 课程介绍", "认识微服务 / Spring Cloud"));
        when(courses.getCourseBaseInfoById(42L)).thenReturn(detail);
        var simple = new CourseSimpleInfoDTO();
        simple.setId(42L); simple.setName("微服务课"); simple.setStatus(2); simple.setPrice(19900);
        when(courses.getSimpleInfoList(List.of(42L))).thenReturn(List.of(simple));
        when(search.queryCoursesIdByName("微服务")).thenReturn(List.of(42L));
    }

    @Test
    void successfulRecommendationKeepsLastToolResultInObservationWithoutAnotherModelCall() {
        var registry = io.micrometer.observation.ObservationRegistry.create();
        var observed = new CopyOnWriteArrayList<org.springframework.ai.tool.observation.ToolCallingObservationContext>();
        registry.observationConfig().observationFilter(new com.tianji.aigc.config.RagObservabilityConfig().ragTraceAttributes(true))
                .observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>() {
                    public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                        return context instanceof org.springframework.ai.tool.observation.ToolCallingObservationContext;
                    }
                    public void onStop(io.micrometer.observation.Observation.Context context) {
                        observed.add((org.springframework.ai.tool.observation.ToolCallingObservationContext) context);
                    }
                });
        var config = new SpringAIConfig();
        client = config.chatClient(ChatClient.builder(model), config.loggerAdvisor(),
                config.messageChatMemoryAdvisor(memory), model, registry);
        when(model.call(any(Prompt.class))).thenReturn(
                tool("", "searchCourses", "{\"keyword\":\"微服务\"}"),
                tool("", "queryCourseById", "{\"courseId\":42}"),
                tool("", "recommendCourses", "{\"selections\":[{\"courseId\":\"42\",\"reason\":\"目录列出Spring Cloud\"}]}"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(text("推荐这门课。")));

        recommend().blockLast(Duration.ofSeconds(5));

        assertThat(observed).extracting(context -> context.getToolDefinition().name())
                .containsExactly("searchCourses", "queryCourseById", "recommendCourses");
        assertThat(observed.get(2).getToolCallResult()).contains("42", "19900").doesNotContain("目录列出Spring Cloud");
        assertThat(observed.get(2).getHighCardinalityKeyValue("langfuse.observation.output").getValue())
                .isEqualTo(observed.get(2).getToolCallResult());
        verify(model, times(3)).call(any(Prompt.class));
    }

    @Test
    void discardsToolRoundProseAndStreamsFinalTokensBeforeCompletion() {
        when(model.call(any(Prompt.class))).thenReturn(
                tool("先给出一遍未核实的结论", "searchCourses", "{\"keyword\":\"微服务\"}"),
                tool("现在再介绍一次", "queryCourseById", "{\"courseId\":42}"),
                tool("推荐前重复一遍", "recommendCourses", "{\"selections\":[{\"courseId\":\"42\",\"reason\":\"目录列出 Spring Cloud\"}]}"),
                text("准备完成"));
        var source = Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            var options = (ToolCallingChatOptions) prompt.getOptions();
            assertThat(options.getToolCallbacks()).isEmpty();
            assertThat(options.getToolNames()).isEmpty();
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            assertThat(prompt.getSystemMessage().getText()).doesNotContain("当前是内部资料准备阶段");
            assertThat(prompt.getInstructions()).filteredOn(m -> m instanceof ToolResponseMessage).hasSize(3);
            assertThat(prompt.getInstructions().stream().filter(m -> m instanceof ToolResponseMessage)
                    .map(m -> ((ToolResponseMessage) m).getResponses()).toList().toString())
                    .contains("Spring Cloud", "199.0");
            assertThat(prompt.getInstructions().stream().map(Message::getText).toList().toString())
                    .doesNotContain("未核实的结论", "介绍一次", "重复一遍", "准备完成");
            return source.asFlux();
        });
        var visible = new CopyOnWriteArrayList<String>();
        var result = recommend().doOnNext(r -> visible.add(r.getResult().getOutput().getText())).collectList().toFuture();
        await().atMost(Duration.ofSeconds(5)).until(() -> result.isDone() || source.currentSubscriberCount() == 1);
        assertThat(result).isNotDone();
        source.tryEmitNext(text("目录列出 "));
        await().atMost(Duration.ofSeconds(3)).until(() -> visible.contains("目录列出 "));
        assertThat(result).isNotDone();
        source.tryEmitNext(text("Spring Cloud。"));
        source.tryEmitComplete();
        await().atMost(Duration.ofSeconds(3)).until(result::isDone);
        assertThat(String.join("", visible)).isEqualTo("目录列出 Spring Cloud。");
        var history = memory.get("42_test");
        assertThat(history).extracting(Message::getText).containsExactly("推荐微服务课", "目录列出 Spring Cloud。");
        assertThat(history.get(1).getMetadata()).containsKey(MessageUtil.COURSE_CARDS);
        assertThat(recommendations.snapshot()).hasSize(1);
        verify(model, times(3)).call(any(Prompt.class));
    }

    @Test
    void prematureRecommendationCanBeRetriedAfterDetailWithoutLeakingIntoHistory() {
        var selection = "{\"selections\":[{\"courseId\":\"42\",\"reason\":\"目录列出 Spring Cloud\"}]}";
        when(model.call(any(Prompt.class))).thenReturn(
                tool("准备候选", "searchCourses", "{\"keyword\":\"微服务\"}"),
                tool("没有详情先推荐", "recommendCourses", selection))
                .thenAnswer(call -> {
                    assertThat(recommendations.snapshot()).isEmpty();
                    Prompt prompt = call.getArgument(0);
                    assertThat(((ToolResponseMessage) prompt.getInstructions().get(prompt.getInstructions().size() - 1)).getResponses())
                            .singleElement().satisfies(response -> assertThat(response.responseData()).isEqualTo("[]"));
                    return tool("补查详情", "queryCourseById", "{\"courseId\":42}");
                })
                .thenReturn(tool("根据目录推荐", "recommendCourses", selection), text("准备完成"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(text("目录列出 Spring Cloud，可进一步了解。")));

        recommend().blockLast(Duration.ofSeconds(5));

        assertThat(recommendations.snapshot()).singleElement()
                .satisfies(card -> assertThat(card.getCourseId()).isEqualTo("42"));
        assertThat(memory.get("42_test")).extracting(Message::getText)
                .containsExactly("推荐微服务课", "目录列出 Spring Cloud，可进一步了解。");
        verify(model, times(4)).call(any(Prompt.class));
    }

    @Test
    void cancellationDuringPreparationDoesNotExecuteReturnedToolOrStartFinalStream() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new AtomicBoolean();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { /* 模拟取消后仍返回响应的阻塞客户端。 */ }
            exited.set(true);
            return tool("取消后不应查询", "searchCourses", "{\"keyword\":\"微服务\"}");
        });
        var subscription = recommend().subscribe();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        release.countDown();
        await().atMost(Duration.ofSeconds(3)).untilTrue(exited);
        verifyNoInteractions(search, courses);
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_test")).noneMatch(m -> m instanceof AssistantMessage);
    }

    @Test
    void preparationFailureDoesNotGenerateAnAnswerOrPersistIntermediateText() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("course query failed"));
        assertThatThrownBy(() -> recommend().blockLast(Duration.ofSeconds(5))).hasRootCauseMessage("course query failed");
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_test")).noneMatch(m -> m instanceof AssistantMessage);
    }

    @Test
    void consultationUsesCurrentDetailAndSavesOnlyTheFinalAnswer() {
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getSystemMessage().getText()).contains("本轮职责：CONSULT", "不以提供个人基础为查询前提");
            return tool("旧价格不应该展示", "queryCourseById", "{\"courseId\":42}");
        }).thenReturn(text("准备完成"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(text("当前价格 199 元。")));
        new ConsultAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext("这门课多少钱", "42_test", recommendations, () -> true, () -> true,
                        null, null, List.of(), null)).blockLast(Duration.ofSeconds(5));
        verify(courses).getCourseBaseInfoById(42L);
        verifyNoInteractions(search);
        assertThat(memory.get("42_test")).extracting(Message::getText)
                .containsExactly("这门课多少钱", "当前价格 199 元。");
        assertThat(recommendations.snapshot()).hasSize(1);
    }

    @Test
    void audienceAnswerBypassesModelRewritingAndKeepsCardAndHistory() {
        var detail = new CourseBaseInfoDTO();
        detail.setId(42L); detail.setName("微服务课"); detail.setPrice(19900);
        detail.setUsePeople("有一定的Java开发基础，熟练使用了SpringBoot、MyBatis等基础框架");
        detail.setCatalogue(List.of("架构演变"));
        when(courses.getCourseBaseInfoById(42L)).thenReturn(detail);
        when(model.call(any(Prompt.class))).thenReturn(tool("准备阶段不该出现的价格199元", "queryCourseById", "{\"courseId\":42}"),
                text("只会Java和SpringBoot就适合，课程覆盖范围有限。"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(text("只会Java和SpringBoot就适合，价格199元。")));
        String question = "微服务课适合什么基础的人？";

        var result = consult(question).collectList().block(Duration.ofSeconds(5));

        assertThat(result).hasSize(2);
        String answer = result.get(0).getResult().getOutput().getText();
        assertThat(answer).contains(detail.getUsePeople()).doesNotContain("199", "目录", "深度", "就适合", "覆盖范围有限");
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_test")).extracting(Message::getText).containsExactly(question, answer);
        assertThat(memory.get("42_test").get(1).getMetadata()).containsKey(MessageUtil.COURSE_CARDS);
        assertThat(recommendations.snapshot()).singleElement().satisfies(card -> assertThat(card.getPriceInCents()).isEqualTo(19900));
    }

    @Test
    void comparisonPreservesBothCoursesAndNeverAddsAHypotheticalMatchConclusion() {
        var first = new CourseBaseInfoDTO(); first.setId(42L); first.setName("前端课"); first.setUsePeople("有一定后端基础");
        var second = new CourseBaseInfoDTO(); second.setId(43L); second.setName("微服务课");
        second.setUsePeople("有Java开发基础，熟练使用SpringBoot、MyBatis");
        when(courses.getCourseBaseInfoById(42L)).thenReturn(first);
        when(courses.getCourseBaseInfoById(43L)).thenReturn(second);
        when(model.call(any(Prompt.class))).thenReturn(tool("", "queryCourseById", "{\"courseId\":42}"),
                tool("", "queryCourseById", "{\"courseId\":43}"), text("会Java和SpringBoot，两门都符合要求。"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(text("会Java和SpringBoot，两门都符合要求。")));

        String answer = consult("前端课和微服务课的适用人群有什么区别？").map(r -> r.getResult().getOutput().getText())
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));

        assertThat(answer).contains(first.getUsePeople(), second.getUsePeople()).doesNotContain("两门都符合要求", "如果");
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void depthAnswerUsesRawBrowseFactsEvenWhenRoutedToRecommendation() {
        var detail = new CourseBaseInfoDTO(); detail.setId(42L); detail.setName("前端课");
        detail.setCatalogue(List.of("HTML / 元素", "JavaScript / 快速入门", "JavaScript / 变量", "Vue / 入门"));
        detail.setDetail("声称深入掌握全部技术。并未提供可验证的教学深度字段。");
        var popular = new com.tianji.api.dto.course.CoursePopularityDTO(); popular.setId(42L); popular.setSold(0);
        when(search.queryPopularCourses(null, null)).thenReturn(List.of(popular));
        when(courses.getBaseInfoBatch(List.of(42L))).thenReturn(List.of(detail));
        when(courses.getCourseBaseInfoById(42L)).thenReturn(detail);
        when(model.call(any(Prompt.class))).thenReturn(tool("", "browseCourses", "{\"free\":null,\"keyword\":null}"),
                tool("目录说明覆盖范围有限", "recommendCourses", "{\"selections\":[{\"courseId\":\"42\",\"reason\":\"目录说明覆盖范围有限\"}]}"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(text("目录里没有更多章节，说明深度和覆盖范围有限。")));

        var result = new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(new AgentContext(
                "前端课中JavaScript讲多深？能确认是完整覆盖的吗？", "42_test", recommendations,
                () -> true, () -> true, null, null, List.of(), null)).collectList().block(Duration.ofSeconds(5));

        String answer = result.get(0).getResult().getOutput().getText();
        assertThat(answer).contains("JavaScript / 快速入门", "JavaScript / 变量", "无法确认讲解深度", "无法确认是否完整覆盖")
                .doesNotContain("覆盖范围有限", "深入掌握", "Vue", "HTML / 元素", "199");
        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_test").get(1).getText()).isEqualTo(answer);
        assertThat(memory.get("42_test").get(1).getMetadata()).containsKey(MessageUtil.COURSE_CARDS);
        assertThat(memory.get("42_test").get(1).getMetadata().toString()).doesNotContain("覆盖范围有限");
        assertThat(recommendations.snapshot()).singleElement().satisfies(card -> {
            assertThat(card.getReason()).isNull();
            assertThat(card.getPriceInCents()).isEqualTo(19900);
        });
    }

    @Test
    void historicalFactsAndPreparationProseCannotReplaceCurrentQueryEvidence() {
        memory.add("42_test", List.of(new org.springframework.ai.chat.messages.UserMessage("之前的要求是什么"),
                new AssistantMessage("旧资料：只需Java，价格为0元。")));
        when(model.call(any(Prompt.class))).thenReturn(text("无需再查，旧资料已经足够。"));

        String answer = consult("微服务课需要什么基础？").map(r -> r.getResult().getOutput().getText())
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));

        assertThat(answer).contains("尚未查询").doesNotContain("只需Java", "0元", "没有匹配");
        verify(model, never()).stream(any(Prompt.class));
        verifyNoInteractions(courses, search);
    }

    @Test
    void ownershipLossAfterPreparationPreventsDeterministicAnswerAndMemoryWrite() {
        var owner = new AtomicBoolean(true);
        when(model.call(any(Prompt.class))).thenReturn(tool("", "queryCourseById", "{\"courseId\":42}"))
                .thenAnswer(call -> { owner.set(false); return text("准备完成"); });

        assertThatThrownBy(() -> new ConsultAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext("微服务课适用人群是什么？", "42_test", recommendations, () -> true, owner::get,
                        null, null, List.of(), null)).blockLast(Duration.ofSeconds(5)))
                .satisfies(error -> assertThat(McpCallScope.isCancellation(error)).isTrue());

        verify(model, never()).stream(any(Prompt.class));
        assertThat(memory.get("42_test")).noneMatch(AssistantMessage.class::isInstance);
    }

    @Test
    void protocolLeakIsFilteredBeforeVisibleOutputAndMemory() {
        when(model.call(any(Prompt.class))).thenReturn(tool("", "queryCourseById", "{\"courseId\":42}"))
                .thenReturn(text("准备完成"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(text("课程资料。"), text("<｜｜D"),
                text("SML｜｜ calls>"), text("不应显示")));
        var replies = consult("微服务课多少钱？").collectList().block(Duration.ofSeconds(5));
        String visible = replies.stream().filter(r -> r.getResult() != null)
                .map(r -> r.getResult().getOutput().getText()).filter(java.util.Objects::nonNull)
                .reduce("", String::concat);
        assertThat(visible).isEqualTo("课程资料。本次回答中断，请重新提问。");
        assertThat(memory.get("42_test")).extracting(Message::getText)
                .containsExactly("微服务课多少钱？", visible);
    }

    private Flux<ChatResponse> consult(String question) {
        return new ConsultAgent(client, prompts, new CourseTools(courses, search)).processStream(new AgentContext(
                question, "42_test", recommendations, () -> true, () -> true, null, null, List.of(), null));
    }

    @Test
    void lostOwnershipBeforeIntakeCompletionDoesNotConsumeAStoredQuestion() {
        when(model.call(any(Prompt.class))).thenReturn(text("准备完成"));
        assertThatThrownBy(() -> new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext("请按我的情况选课", "42_test", recommendations, () -> true, () -> false,
                        null, null, List.of(), null)).blockLast(Duration.ofSeconds(5)))
                .satisfies(error -> assertThat(McpCallScope.isCancellation(error)).isTrue());
        assertThat(memory.get("42_test")).noneMatch(AssistantMessage.class::isInstance);
        var next = new CourseRecommendations();
        next.restoreIntake(memory.get("42_test"));
        assertThat(next.intakeQuestions()).isZero();
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void noQueryKeepsRequirementIntakeDistinctFromNoMatchingCourses() {
        when(model.call(any(Prompt.class))).thenReturn(text("准备完成"));
        String question = "我不需要热门课程，我需要你给我量身定制课程";
        new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext(question, "42_test", recommendations, () -> true, () -> true,
                        null, null, List.of(), null)).blockLast(Duration.ofSeconds(5));
        verifyNoInteractions(search, courses);
        assertThat(recommendations.snapshot()).isEmpty();
        assertThat(memory.get("42_test")).extracting(Message::getText)
                .containsExactly(question, "你目前学过哪些编程知识，能独立做点什么？");
        assertThat(memory.get("42_test").get(1).getMetadata()).containsEntry(CourseRecommendations.INTAKE_STATE, 1);
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void noQueryDoesNotAskForFactsRestoredFromLongTermMemory() {
        recommendations.restoreKnownFacts(true, true);
        when(model.call(any(Prompt.class))).thenReturn(text("准备完成"));
        String question = "帮我推荐课程";
        new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext(question, "42_test", recommendations, () -> true, () -> true,
                        null, null, List.of(), null)).blockLast(Duration.ofSeconds(5));
        verifyNoInteractions(search, courses);
        assertThat(memory.get("42_test")).extracting(Message::getText)
                .containsExactly(question, "你已提供基础和学习目标，但本次尚未完成课程查询，暂时不能给出有依据的个性化推荐。");
    }

    @Test
    void noQueryConclusionDoesNotPersistRecommendationCompletion() {
        when(model.call(any(Prompt.class))).thenReturn(text("准备完成"));
        String question = "我会Java，想学HTML做网页";
        new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext(question, "42_test", recommendations, () -> true, () -> true,
                        null, null, List.of(), null)).blockLast(Duration.ofSeconds(5));

        verifyNoInteractions(search, courses);
        assertThat(memory.get("42_test")).hasSize(2);
        assertThat(memory.get("42_test").get(1).getMetadata()).doesNotContainKey(CourseRecommendations.INTAKE_STATE);
        var next = new CourseRecommendations();
        next.restoreIntake(memory.get("42_test"));
        assertThat(next.nextIntakeReply()).contains("尚未完成课程查询").doesNotContain("实际问题");
    }

    @Test
    void emptySearchResultsRemainEmptyAndDoNotCreateCards() {
        when(search.queryCoursesIdByName("微服务")).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenReturn(
                tool("", "searchCourses", "{\"keyword\":\"微服务\"}"), text("准备完成"));
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getSystemMessage().getText()).contains("本轮已执行课程查询")
                    .doesNotContain("本轮未执行课程查询");
            return Flux.just(text("本次没有找到匹配课程。"));
        });
        recommend().blockLast(Duration.ofSeconds(5));
        assertThat(recommendations.snapshot()).isEmpty();
        verifyNoInteractions(courses);
        assertThat(memory.get("42_test")).extracting(Message::getText)
                .containsExactly("推荐微服务课", "本次没有找到匹配课程。");
    }

    @Test
    void courseOverviewDoesNotFeedCatalogueOrUnaskedCoverageNotesToEitherStage() {
        var detail = new CourseBaseInfoDTO();
        detail.setId(42L); detail.setName("微服务课"); detail.setPrice(19900);
        detail.setUsePeople("有 Java 基础，熟练使用 SpringBoot、MyBatis");
        detail.setDetail("<h3>课程内容</h3><p>介绍微服务架构演变和 SpringCloud。</p>"
                + "<h3>当前正式目录</h3><ul><li>认识微服务 / SpringCloud</li></ul>"
                + "<h3>资料范围</h3><p>现有资料未说明讲解深度、完整实战项目及后续章节安排。</p>");
        detail.setCatalogue(List.of("认识微服务 / SpringCloud"));
        when(courses.getCourseBaseInfoById(42L)).thenReturn(detail);
        when(model.call(any(Prompt.class))).thenReturn(tool("", "queryCourseById", "{\"courseId\":42}"))
                .thenAnswer(call -> {
                    assertThat(toolResults(call.getArgument(0))).contains("微服务架构演变", "SpringBoot", "MyBatis")
                            .doesNotContain("认识微服务 / SpringCloud", "深度", "完整实战", "后续章节");
                    return text("准备完成");
                });
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            assertThat(toolResults(call.getArgument(0))).contains("微服务架构演变")
                    .doesNotContain("认识微服务 / SpringCloud", "深度", "完整实战", "后续章节");
            return Flux.just(text("这门课介绍微服务架构，需要 Java 和基础框架经验。"));
        });

        new ConsultAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext("第二个是啥", "42_test", recommendations, () -> true, () -> true,
                        null, null, List.of(), null)).blockLast(Duration.ofSeconds(5));

        assertThat(recommendations.snapshot()).singleElement()
                .satisfies(card -> assertThat(card.getPriceInCents()).isEqualTo(19900));
        assertThat(detail.getDetail()).contains("完整实战");
        assertThat(detail.getCatalogue()).containsExactly("认识微服务 / SpringCloud");
    }

    @Test
    void currentWebGoalDoesNotInheritFrameworksFromAssistantHistoryOrToolArguments() {
        var detail = new CourseBaseInfoDTO();
        detail.setId(42L); detail.setName("Java程序员的前端课程"); detail.setPrice(19900);
        detail.setUsePeople("有一定后端基础");
        detail.setDetail("<h3>课程定位与适用人群</h3><p>有一定后端基础，想学习前端知识的同学。</p>"
                + "<h3>当前正式目录</h3><ul><li>Html与CSS / HTML元素</li><li>JavaScript / 变量</li></ul>"
                + "<h3>原介绍声明的其他内容</h3><p>原介绍涉及 Vue2、Vue3、React、jQuery，当前目录未列出这些框架。</p>");
        detail.setCatalogue(List.of("Html与CSS / HTML元素", "Html与CSS / CSS", "JavaScript / 变量", "Vue / 入门"));
        when(courses.getCourseBaseInfoById(42L)).thenReturn(detail);
        memory.add("42_test", List.of(new org.springframework.ai.chat.messages.UserMessage("是否教 Vue？"),
                new AssistantMessage("介绍涉及 Vue、React、jQuery。")));
        when(model.call(any(Prompt.class))).thenReturn(tool("", "queryCourseById", "{\"courseId\":42}"))
                .thenAnswer(call -> {
                    assertThat(toolResults(call.getArgument(0))).contains("HTML元素", "CSS", "JavaScript / 变量")
                            .doesNotContain("Vue", "React", "jQuery", "未列出");
                    return text("准备完成");
                });
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            assertThat(toolResults(call.getArgument(0))).contains("HTML元素", "JavaScript / 变量")
                    .doesNotContain("Vue", "React", "jQuery", "未列出");
            return Flux.just(text("可以先看这门前端课，相关目录包括 HTML元素、CSS 和 JavaScript 变量。"));
        });

        new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext("我学过 Java、Spring Boot、MyBatis，想学 HTML/CSS/JavaScript 做网页", "42_test",
                        recommendations, () -> true, () -> true, null, null, List.of(), null))
                .blockLast(Duration.ofSeconds(5));
    }

    private String toolResults(Prompt prompt) {
        return prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData).collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void continuationInheritsOnlyTheUsersLearningGoalAndNeverHistoricalFullContentPermission() {
        memory.add("42_test", List.of(
                new org.springframework.ai.chat.messages.UserMessage("想学 HTML 和 CSS"),
                new AssistantMessage("还可以看 Vue。"),
                new org.springframework.ai.chat.messages.UserMessage("是否教 React？"),
                new AssistantMessage("需要核对目录。")));
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            var scope = (CourseTools.DetailScope) ((ToolCallingChatOptions) prompt.getOptions())
                    .getToolContext().get(CourseTools.DETAIL_SCOPE);
            assertThat(scope.fullContent()).isFalse();
            assertThat(scope.topics()).contains("html", "css").doesNotContain("vue", "react");
            return text("准备完成");
        });
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(text("继续按网页目标选课。")));
        new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext("继续推荐", "42_test", recommendations, () -> true, () -> true,
                        null, null, List.of(), null)).blockLast(Duration.ofSeconds(5));
    }

    @Test
    void boundedPreparationRejectsRepeatedToolCalls() {
        when(model.call(any(Prompt.class))).thenReturn(tool("", "searchCourses", "{\"keyword\":\"微服务\"}"));
        assertThatThrownBy(() -> recommend().blockLast(Duration.ofSeconds(5))).satisfies(error -> assertThat(com.tianji.aigc.tools.CourseToolPreparationAdvisor.isToolRoundLimit(error)).isTrue());
        verify(search, times(8)).queryCoursesIdByName("微服务");
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void fifthTurnIntakeUsesPersistedStepWithoutAnotherAnswerModelCall() {
        String priorQuestion = "你目前学过哪些编程知识，能独立做点什么？";
        String nextQuestion = "你希望学完后能做出什么，或者解决什么实际问题？";
        memory.add("42_test", List.of(new org.springframework.ai.chat.messages.UserMessage("我也不知道"),
                AssistantMessage.builder().content(priorQuestion)
                        .properties(java.util.Map.of(CourseRecommendations.INTAKE_STATE, 1)).build(),
                new org.springframework.ai.chat.messages.UserMessage("第二个是啥"),
                new AssistantMessage("第二门主要介绍微服务。")));
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getInstructions()).filteredOn(AssistantMessage.class::isInstance)
                    .extracting(Message::getText).contains(priorQuestion);
            assertThat(prompt.getSystemMessage().getText()).contains("已问但未回答", "换一种说法仍属于重复追问");
            return text("准备完成");
        });

        new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(new AgentContext(
                "有没有课程推荐", "42_test", recommendations, () -> true, () -> true,
                null, null, List.of(), null)).blockLast(Duration.ofSeconds(5));

        verifyNoInteractions(courses, search);
        verify(model, times(1)).call(any(Prompt.class));
        verify(model, never()).stream(any(Prompt.class));
        assertThat(recommendations.snapshot()).isEmpty();
        var history = memory.get("42_test");
        assertThat(history.subList(history.size() - 2, history.size())).extracting(Message::getText)
                .containsExactly("有没有课程推荐", nextQuestion);
    }

    @ParameterizedTest
    @ValueSource(strings = {"有哪些热门课程推荐？", "有没有课程推荐"})
    void browsingCardReasonRulesReachPreparationAndToolSchemaBeforeCardSubmission(String question) {
        memory.add("42_test", List.of(AssistantMessage.builder().content("已经问过基础")
                .properties(java.util.Map.of(CourseRecommendations.INTAKE_STATE, 1)).build()));
        var detail = new CourseBaseInfoDTO();
        detail.setId(42L); detail.setName("Java程序员的前端课程");
        detail.setUsePeople("有后端基础，想学习前端的开发者");
        detail.setDetail("面向后端开发者的前端知识");
        detail.setCatalogue(List.of("HTML", "CSS", "JavaScript"));
        var simple = new CourseSimpleInfoDTO();
        simple.setId(42L); simple.setName(detail.getName()); simple.setStatus(2); simple.setPrice(19900);
        when(courses.getSimpleInfoList(List.of(42L))).thenReturn(List.of(simple));
        var popularity = new com.tianji.api.dto.course.CoursePopularityDTO();
        popularity.setId(42L); popularity.setSold(0);
        when(search.queryPopularCourses(null, null)).thenReturn(List.of(popularity));
        when(courses.getBaseInfoBatch(List.of(42L))).thenReturn(List.of(detail));
        when(courses.getCourseBaseInfoById(42L)).thenReturn(detail);
        String reason = "适用人群：有后端基础，想学习前端的开发者";
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            var options = (ToolCallingChatOptions) prompt.getOptions();
            var definition = options.getToolCallbacks().stream()
                    .filter(callback -> callback.getToolDefinition().name().equals("recommendCourses"))
                    .findFirst().orElseThrow().getToolDefinition();
            assertThat(definition.description()).contains("适用人群原文", "reason不作为展示依据");
            assertThat(definition.inputSchema()).contains("兼容旧调用");
            return tool("", "browseCourses", "{\"free\":null,\"keyword\":null}");
        }).thenAnswer(call -> {
            assertThat(toolResults(call.getArgument(0))).contains("notExpanded", "有后端基础");
            return tool("", "recommendCourses", "{\"selections\":[{\"courseId\":\"42\",\"reason\":\"目录未展开，进详情页核对；零基础都适合\"}]}");
        });

        new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(new AgentContext(
                question, "42_test", recommendations, () -> true, () -> true,
                null, null, List.of(), null)).blockLast(Duration.ofSeconds(5));

        assertThat(recommendations.snapshot()).singleElement().satisfies(card -> assertThat(card.getReason()).isEqualTo(reason));
        var history = memory.get("42_test");
        var last = history.get(history.size()-1);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(last
                .getMetadata().get(MessageUtil.COURSE_CARDS)).get(0).path("reason").asText()).isEqualTo(reason);
        verify(model, times(2)).call(any(Prompt.class));
        assertThat(last.getText()).contains("有后端基础")
                .doesNotContain("未展开", "核对", "零基础都适合", "？", "?");
        assertThat(last.getMetadata()).doesNotContainKey(CourseRecommendations.INTAKE_STATE);
        var restored = new CourseRecommendations();
        restored.restoreIntake(history);
        assertThat(restored.intakeQuestions()).isEqualTo(1);
        verify(model, never()).stream(any(Prompt.class));
        verify(courses).getCourseBaseInfoById(42L);
    }

    private Flux<ChatResponse> recommend() {
        return new RecommendAgent(client, prompts, new CourseTools(courses, search)).processStream(
                new AgentContext("推荐微服务课", "42_test", recommendations, () -> true, () -> true,
                        null, null, List.of(), null));
    }

    private ChatResponse text(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }

    private ChatResponse tool(String text, String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall(name + "-call", "function", name, arguments))).build())));
    }
}
