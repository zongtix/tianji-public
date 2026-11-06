package com.tianji.aigc.agent;

import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.tools.CourseRecommendations;
import com.tianji.aigc.tools.result.CourseCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentDispatcherPlatformRuleTest {
    private final EnumMap<AgentTypeEnum, Agent> agents = new EnumMap<>(AgentTypeEnum.class);
    private AgentDispatcher dispatcher;
    private ChatMemory memory;

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
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any()))
                .thenReturn(Flux.just(reply("{\"agent\":\"RECOMMEND\"}")));
        dispatcher = new AgentDispatcher(List.copyOf(agents.values()), memory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"推荐一门讲优惠券领取的课程", "有没有讲签到功能实现的课程",
            "有哪些课程讲积分规则实现", "签到系统课程推荐"})
    void courseDiscoveryReachesClassifierAndSelectedCourseAgent(String question) {
        assertThat(dispatcher.processStream(context(question, false)).collectList().block(Duration.ofSeconds(5)))
                .extracting(response -> response.getResult().getOutput().getText())
                .containsExactly("RECOMMEND");
        verify(agents.get(AgentTypeEnum.ROUTE)).processStream(any());
        verify(agents.get(AgentTypeEnum.RECOMMEND)).processStream(any());
        verify(agents.get(AgentTypeEnum.KNOWLEDGE), never()).processStream(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"优惠券领取规则", "连续签到第七天多少分", "课程优惠券有哪些使用规则"})
    void platformRuleKeepsKnowledgeShortcut(String question) {
        assertThat(dispatcher.processStream(context(question, false)).collectList().block(Duration.ofSeconds(5)))
                .extracting(response -> response.getResult().getOutput().getText())
                .containsExactly("KNOWLEDGE");
        verify(agents.get(AgentTypeEnum.ROUTE), never()).processStream(any());
    }

    @Test
    void boundKnowledgeSessionKeepsItsServerSelectedBranch() {
        assertThat(dispatcher.processStream(context("推荐一门讲优惠券领取的课程", true))
                .collectList().block(Duration.ofSeconds(5)))
                .extracting(response -> response.getResult().getOutput().getText())
                .containsExactly("KNOWLEDGE");
        verify(agents.get(AgentTypeEnum.ROUTE), never()).processStream(any());
        verify(agents.get(AgentTypeEnum.RECOMMEND), never()).processStream(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "这门课需要签到吗",
            "第二门课有签到环节吗",
            "讲义里的兑换码在哪一页"
    })
    void courseOrLectureReferenceWithBareRuleWordsReachesClassifier(String question) {
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any()))
                .thenReturn(Flux.just(reply("{\"agent\":\"CONSULT\"}")));
        assertThat(dispatcher.processStream(context(question, false)).collectList().block(Duration.ofSeconds(5)))
                .extracting(response -> response.getResult().getOutput().getText())
                .containsExactly("CONSULT");
        verify(agents.get(AgentTypeEnum.ROUTE)).processStream(any());
        verify(agents.get(AgentTypeEnum.KNOWLEDGE), never()).processStream(any());
    }

    @Test
    void historyCardsDoNotMakeBareCheckInAPlatformShortcut() {
        when(memory.get("session")).thenReturn(List.of(
                new UserMessage("有哪些热门课程推荐？"),
                AssistantMessage.builder().content("可先看这两门。")
                        .properties(Map.of(MessageUtil.COURSE_CARDS, List.of(
                                new CourseCard("1", "前端课", null, 14900, 12, null),
                                new CourseCard("2", "微服务课", null, 19900, 12, null)))).build()));
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any()))
                .thenReturn(Flux.just(reply("{\"agent\":\"CONSULT\"}")));

        assertThat(dispatcher.processStream(context("第二门课有签到环节吗", false))
                .collectList().block(Duration.ofSeconds(5)))
                .extracting(response -> response.getResult().getOutput().getText())
                .containsExactly("CONSULT");
        verify(agents.get(AgentTypeEnum.ROUTE)).processStream(any());
        verify(agents.get(AgentTypeEnum.KNOWLEDGE), never()).processStream(any());
    }

    @Test
    void withoutHistoryClearPlatformRuleStillShortcutsToKnowledge() {
        assertThat(dispatcher.processStream(context("签到得多少积分", false))
                .collectList().block(Duration.ofSeconds(5)))
                .extracting(response -> response.getResult().getOutput().getText())
                .containsExactly("KNOWLEDGE");
        verify(agents.get(AgentTypeEnum.ROUTE), never()).processStream(any());
    }

    private AgentContext context(String question, boolean boundKnowledge) {
        return new AgentContext(question, "session", new CourseRecommendations(), () -> true, () -> true,
                boundKnowledge ? mock(KnowledgeRetrievalAdvisor.class) : null,
                boundKnowledge ? "server-filter" : null, List.of(), null);
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
