package com.tianji.aigc.agent;

import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.tools.CourseRecommendations;
import com.tianji.aigc.tools.result.CourseCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * L1 / L12 / L13：路由完成回调线程、legacyCourseIntake 优先级、卡片上限与序号定位。
 * <p>本文件未在本子代理中运行 Maven；不接真实模型或业务服务。</p>
 */
class RemainingRoutingCardsIntakeTest {
    private final EnumMap<AgentTypeEnum, Agent> agents = new EnumMap<>(AgentTypeEnum.class);
    private ChatMemory memory;
    private AgentDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        memory = mock(ChatMemory.class);
        when(memory.get("session")).thenReturn(List.of());
        for (var type : AgentTypeEnum.values()) {
            var agent = mock(Agent.class);
            when(agent.getAgentType()).thenReturn(type);
            when(agent.processStream(any())).thenReturn(Flux.just(reply(type.name())));
            agents.put(type, agent);
        }
        dispatcher = new AgentDispatcher(List.copyOf(agents.values()), memory);
    }

    @Test
    void activeCheckAfterClassifyRunsOnBoundedElastic() {
        var threadName = new AtomicReference<String>();
        // 故意把路由结果发到非 boundedElastic 线程，证明完成回调上的 active 检查被切走。
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                        "{\"agent\":\"CONSULT\",\"clarification\":\"\"}"))
                .publishOn(Schedulers.newSingle("route-model-thread")));

        var context = new AgentContext("随便聊聊天气", "session", new CourseRecommendations(),
                () -> {
                    threadName.set(Thread.currentThread().getName());
                    return true;
                },
                () -> true, null, null, List.of(), null);

        dispatcher.processStream(context).blockLast(Duration.ofSeconds(5));

        assertThat(threadName.get()).isNotNull().contains("boundedElastic");
        assertThat(threadName.get()).doesNotContain("route-model-thread");
        verify(agents.get(AgentTypeEnum.CONSULT)).processStream(any());
    }

    @Test
    void inactiveAfterRouteKeepsNeverSemanticsInsteadOfEmptyComplete() throws Exception {
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                        "{\"agent\":\"CONSULT\",\"clarification\":\"\"}"))
                .publishOn(Schedulers.newSingle("route-model-thread")));
        var context = new AgentContext("随便聊聊天气", "session", new CourseRecommendations(),
                () -> false, () -> true, null, null, List.of(), null);

        var completed = new AtomicBoolean();
        var errored = new AtomicBoolean();
        var subscription = dispatcher.processStream(context)
                .subscribe(ignored -> { }, error -> errored.set(true), () -> completed.set(true));
        assertThat(subscription.isDisposed()).isFalse();
        Thread.sleep(250);
        assertThat(completed).isFalse();
        assertThat(errored).isFalse();
        subscription.dispose();
        verify(agents.get(AgentTypeEnum.CONSULT), never()).processStream(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"请帮我选课", "我想量身定制课程", "按我的情况推荐一门课"})
    void legacyExplicitIntakeNeedsNoCardGate(String question) {
        // A || (B && C)：明确选课关键词单独成立，不要求已有卡片或 intake 计数。
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                "{\"agent\":null,\"clarification\":\"模型随便问一句\"}")));

        dispatcher.processStream(context(question, List.of())).blockLast(Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(AgentContext.class);
        verify(agents.get(AgentTypeEnum.KNOWLEDGE)).processStream(captor.capture());
        assertThat(captor.getValue().clarification())
                .isEqualTo("你目前学过哪些编程知识，能独立做点什么？")
                .isNotEqualTo("模型随便问一句");
    }

    @Test
    void legacyIDontKnowWithoutCardsIsNotForcedIntake() {
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                "{\"agent\":null,\"clarification\":\"模型澄清文案\"}")));

        dispatcher.processStream(context("我也不知道", List.of())).blockLast(Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(AgentContext.class);
        verify(agents.get(AgentTypeEnum.KNOWLEDGE)).processStream(captor.capture());
        // B && C 不成立时保持模型澄清，不因机械加括号把 A||B 整段塞进 intake。
        assertThat(captor.getValue().clarification()).isEqualTo("模型澄清文案");
    }

    @Test
    void legacyIDontKnowWithCardsUsesServerIntake() {
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                "{\"agent\":null,\"clarification\":\"模型澄清文案\"}")));
        List<Message> history = List.of(AssistantMessage.builder().content("推荐如下")
                .properties(Map.of(MessageUtil.COURSE_CARDS, List.of(
                        Map.of("courseId", "1", "name", "前端课"),
                        Map.of("courseId", "2", "name", "Java课")))).build());
        when(memory.get("session")).thenReturn(history);

        dispatcher.processStream(context("我也不知道", history)).blockLast(Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(AgentContext.class);
        verify(agents.get(AgentTypeEnum.KNOWLEDGE)).processStream(captor.capture());
        assertThat(captor.getValue().clarification())
                .isEqualTo("你目前学过哪些编程知识，能独立做点什么？");
    }

    @Test
    void fourthOrdinalDoesNotHitThreeCardClarificationPath() {
        List<Message> history = List.of(AssistantMessage.builder().content("三门课")
                .properties(Map.of(MessageUtil.COURSE_CARDS, List.of(
                        Map.of("courseId", "1", "name", "课一"),
                        Map.of("courseId", "2", "name", "课二"),
                        Map.of("courseId", "3", "name", "课三")))).build());
        when(memory.get("session")).thenReturn(history);
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                "{\"agent\":\"CONSULT\",\"clarification\":\"\"}")));

        dispatcher.processStream(context("第四门是什么", history)).blockLast(Duration.ofSeconds(5));

        verify(agents.get(AgentTypeEnum.ROUTE)).processStream(any());
        verify(agents.get(AgentTypeEnum.CONSULT)).processStream(any());
        verify(agents.get(AgentTypeEnum.KNOWLEDGE), never()).processStream(any());
    }

    @Test
    void detailCardsAndHistoryReferencesShareThreeCardCap() {
        var recommendations = new CourseRecommendations();
        for (int i = 1; i <= 5; i++) {
            recommendations.addDetailCard(new CourseCard(String.valueOf(i), "课" + i, null, 100, 12, null));
        }
        assertThat(recommendations.snapshot()).hasSize(3);

        List<Map<String, String>> four = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            four.add(Map.of("courseId", String.valueOf(i), "name", "课" + i));
        }
        List<Message> history = List.of(AssistantMessage.builder().content("四门")
                .properties(Map.of(MessageUtil.COURSE_CARDS, four)).build());
        assertThat(AgentHistory.latestCards(history)).hasSize(3)
                .extracting(AgentHistory.CourseReference::courseId)
                .containsExactly("1", "2", "3");
    }

    @Test
    void thirdOrdinalStillWorksWhenHistoryHadFourStoredCards() {
        List<Map<String, String>> four = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            four.add(Map.of("courseId", String.valueOf(i), "name", "课" + i));
        }
        List<Message> history = List.of(AssistantMessage.builder().content("展示")
                .properties(Map.of(MessageUtil.COURSE_CARDS, four)).build());
        when(memory.get("session")).thenReturn(history);
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                "{\"agent\":\"CONSULT\",\"clarification\":\"\"}")));

        // 序号正则只认 1/2/3；第三门命中后走模型路由而非“只展示了 N 门”。
        dispatcher.processStream(context("第三门是什么", history)).blockLast(Duration.ofSeconds(5));
        verify(agents.get(AgentTypeEnum.CONSULT)).processStream(any());
        verify(agents.get(AgentTypeEnum.KNOWLEDGE), never()).processStream(argThat(
                ctx -> ctx.clarification() != null && ctx.clarification().contains("只展示了")));
    }

    private AgentContext context(String question, List<Message> history) {
        return new AgentContext(question, "session", new CourseRecommendations(), () -> true, () -> true,
                null, null, history, null);
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
