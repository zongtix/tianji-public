package com.tianji.aigc.agent;

import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.tools.CourseRecommendations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** M5：intake 类型澄清以服务端固定文案为准，模型 clarification 空/过长不整轮失败。 */
class AgentDispatcherIntakeClarificationTest {
    private final EnumMap<AgentTypeEnum, Agent> agents = new EnumMap<>(AgentTypeEnum.class);
    private AgentDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        var memory = mock(ChatMemory.class);
        when(memory.get("session")).thenReturn(List.of());
        for (var type : AgentTypeEnum.values()) {
            var agent = mock(Agent.class);
            when(agent.getAgentType()).thenReturn(type);
            when(agent.processStream(any())).thenReturn(Flux.just(reply(type.name())));
            agents.put(type, agent);
        }
        dispatcher = new AgentDispatcher(List.copyOf(agents.values()), memory);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"agent\":null,\"clarificationType\":\"COURSE_INTAKE\"}",
            "{\"agent\":null,\"clarification\":\"\",\"clarificationType\":\"COURSE_INTAKE\"}",
            "{\"agent\":null,\"clarification\":\" \",\"clarificationType\":\"COURSE_BASICS\"}",
            "{\"agent\":null,\"clarification\":\"x\",\"clarificationType\":\"COURSE_GOAL\"}"
    })
    void intakeAllowsMissingOrEmptyClarificationAndUsesServerReply(String routeJson) {
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(routeJson)));

        dispatcher.processStream(context("请帮我选课")).blockLast(Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(AgentContext.class);
        verify(agents.get(AgentTypeEnum.KNOWLEDGE)).processStream(captor.capture());
        assertThat(captor.getValue().clarification()).isIn(
                "你目前学过哪些编程知识，能独立做点什么？",
                "你希望学完后能做出什么，或者解决什么实际问题？");
        verify(agents.get(AgentTypeEnum.RECOMMEND), never()).processStream(any());
    }

    @Test
    void intakeAllowsOversizedClarificationWithoutFailingTheTurn() {
        String longText = "请补充".repeat(80);
        assertThat(longText.length()).isGreaterThan(200);
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                "{\"agent\":null,\"clarification\":\"" + longText + "\",\"clarificationType\":\"COURSE_INTAKE\"}")));

        dispatcher.processStream(context("量身定制课程")).blockLast(Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(AgentContext.class);
        verify(agents.get(AgentTypeEnum.KNOWLEDGE)).processStream(captor.capture());
        assertThat(captor.getValue().clarification())
                .isEqualTo("你目前学过哪些编程知识，能独立做点什么？")
                .isNotEqualTo(longText);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"agent\":null,\"clarification\":\"\",\"clarificationType\":\"GENERAL\"}",
            "{\"agent\":null,\"clarification\":\" \",\"clarificationType\":\"COURSE_REFERENCE\"}"
    })
    void generalAndReferenceClarificationRemainStrict(String routeJson) {
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(routeJson)));

        assertThatThrownBy(() -> dispatcher.processStream(context("这门怎么样？")).blockLast(Duration.ofSeconds(5)))
                .matches(AgentDispatcher::isRoutingFailure);
        verify(agents.get(AgentTypeEnum.KNOWLEDGE), never()).processStream(any());
    }

    @Test
    void generalClarificationStillRejectsOversizedText() {
        String longText = "请补充详情啦".repeat(40);
        assertThat(longText.length()).isGreaterThan(200);
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(
                "{\"agent\":null,\"clarification\":\"" + longText + "\",\"clarificationType\":\"GENERAL\"}")));

        assertThatThrownBy(() -> dispatcher.processStream(context("这门怎么样？")).blockLast(Duration.ofSeconds(5)))
                .matches(AgentDispatcher::isRoutingFailure);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"agent\":\"CONSULT\",\"clarification\":\"请补充\",\"clarificationType\":\"COURSE_INTAKE\"}",
            "{\"agent\":\"CONSULT\",\"clarification\":\"请补充\"}",
            "{\"agent\":\"ROUTE\"}"
    })
    void agentMixedOrInvalidProtocolStillFails(String routeJson) {
        when(agents.get(AgentTypeEnum.ROUTE).processStream(any())).thenReturn(Flux.just(reply(routeJson)));

        assertThatThrownBy(() -> dispatcher.processStream(context("问题")).blockLast(Duration.ofSeconds(5)))
                .matches(AgentDispatcher::isRoutingFailure);
    }

    private AgentContext context(String question) {
        return new AgentContext(question, "session", new CourseRecommendations(), () -> true, () -> true,
                null, null, List.of(), null);
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
