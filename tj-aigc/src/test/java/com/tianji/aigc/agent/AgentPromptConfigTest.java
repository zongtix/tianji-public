package com.tianji.aigc.agent;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.tianji.aigc.config.AIProperties;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.enums.AgentTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPromptConfigTest {
    @Test
    void loadsUtf8DefaultsWithoutNacosConfiguration() {
        var manager = mock(NacosConfigManager.class);
        var prompts = new SystemPromptConfig(manager, new AIProperties());
        prompts.init();

        assertThat(prompts.getChatSystemMessage().get()).contains("你是天机学堂的 AI 学习助手", "{now}");
        assertThat(prompts.agentMessage(AgentTypeEnum.ROUTE)).contains("意图路由器", "仅输出一个 JSON 对象",
                "平台积分、签到、优惠券规则归 KNOWLEDGE");
        assertThat(prompts.agentMessage(AgentTypeEnum.RECOMMEND)).contains("你负责课程推荐", "recommendCourses");
        assertThat(prompts.agentMessage(AgentTypeEnum.CONSULT)).contains("你负责课程咨询");
        assertThat(prompts.agentMessage(AgentTypeEnum.KNOWLEDGE))
                .contains("你负责知识讲解", "searchPublicRules")
                .doesNotContain("本分支没有业务工具");
        assertThat(prompts.getCourseCardContextMessage()).contains("仅用于识别指代", "{recentCourseCards}");
        verifyNoInteractions(manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "blank"})
    void missingOrBlankDefaultFileFailsStartup(String mode) {
        var thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        var loader = new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.equals("prompts/route-system-message.txt")) {
                    if (mode.equals("missing")) return null;
                    String content = mode.equals("empty") ? "" : " \n\t";
                    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
                }
                return super.getResourceAsStream(name);
            }
        };
        try {
            thread.setContextClassLoader(loader);
            var prompts = new SystemPromptConfig(mock(NacosConfigManager.class), new AIProperties());
            assertThatThrownBy(prompts::init).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("prompts/route-system-message.txt");
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void bindsAgentNamesFromApplicationProperties() {
        var environment = new MockEnvironment()
                .withProperty("tj.ai.prompt.system.chat.data-id", "system-chat-message.txt")
                .withProperty("tj.ai.prompt.system.agents.route.data-id", "agent-route.txt")
                .withProperty("tj.ai.prompt.system.agents.consult.group", "AGENT_GROUP");
        var properties = Binder.get(environment).bind("tj.ai.prompt", Bindable.of(AIProperties.class)).get();
        assertThat(properties.getSystem().getAgents().get(AgentTypeEnum.ROUTE).getDataId()).isEqualTo("agent-route.txt");
        assertThat(properties.getSystem().getAgents().get(AgentTypeEnum.CONSULT).getGroup()).isEqualTo("AGENT_GROUP");
        assertThat(properties.getSystem().getChat().getDataId()).isEqualTo("system-chat-message.txt");
    }

    @Test
    void keepsExistingChatPromptAndHotUpdatesOnlyConfiguredAgent() throws Exception {
        var manager = mock(NacosConfigManager.class);
        var nacos = mock(ConfigService.class);
        when(manager.getConfigService()).thenReturn(nacos);
        when(nacos.getConfig(eq("system-chat-message.txt"), eq("DEFAULT_GROUP"), anyLong())).thenReturn("公共规则");
        when(nacos.getConfig(eq("agent-route.txt"), eq("DEFAULT_GROUP"), anyLong())).thenReturn("路由规则一");
        var properties = new AIProperties();
        var system = new AIProperties.System();
        system.setChat(config("system-chat-message.txt"));
        system.setAgents(Map.of(AgentTypeEnum.ROUTE, config("agent-route.txt")));
        properties.setSystem(system);
        var prompts = new SystemPromptConfig(manager, properties);
        prompts.init();

        assertThat(prompts.getChatSystemMessage().get()).isEqualTo("公共规则");
        assertThat(prompts.agentMessage(AgentTypeEnum.ROUTE)).isEqualTo("路由规则一");
        assertThat(prompts.agentMessage(AgentTypeEnum.CONSULT)).contains("你负责课程咨询");
        var listener = ArgumentCaptor.forClass(Listener.class);
        verify(nacos).addListener(eq("agent-route.txt"), eq("DEFAULT_GROUP"), listener.capture());
        listener.getValue().receiveConfigInfo("路由规则二");
        assertThat(prompts.agentMessage(AgentTypeEnum.ROUTE)).isEqualTo("路由规则二");
        listener.getValue().receiveConfigInfo(" ");
        assertThat(prompts.agentMessage(AgentTypeEnum.ROUTE)).contains("意图路由器");
        assertThat(prompts.getChatSystemMessage().get()).isEqualTo("公共规则");
        verify(nacos, times(2)).getConfig(anyString(), anyString(), anyLong());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t"})
    void blankNacosValuesUseDefaultsOnStartupAndHotUpdate(String blank) throws Exception {
        var manager = mock(NacosConfigManager.class);
        var nacos = mock(ConfigService.class);
        when(manager.getConfigService()).thenReturn(nacos);
        when(nacos.getConfig(anyString(), anyString(), anyLong())).thenReturn(blank);
        var properties = new AIProperties();
        properties.getSystem().setChat(config("system-chat-message.txt"));
        properties.getSystem().setAgents(Map.of(AgentTypeEnum.CONSULT, config("agent-consult.txt")));
        var prompts = new SystemPromptConfig(manager, properties);
        prompts.init();
        String commonDefault = prompts.getChatSystemMessage().get();
        String consultDefault = prompts.agentMessage(AgentTypeEnum.CONSULT);
        assertThat(commonDefault).contains("你是天机学堂的 AI 学习助手");
        assertThat(consultDefault).contains("你负责课程咨询");

        var commonListener = ArgumentCaptor.forClass(Listener.class);
        var consultListener = ArgumentCaptor.forClass(Listener.class);
        verify(nacos).addListener(eq("system-chat-message.txt"), eq("DEFAULT_GROUP"), commonListener.capture());
        verify(nacos).addListener(eq("agent-consult.txt"), eq("DEFAULT_GROUP"), consultListener.capture());
        commonListener.getValue().receiveConfigInfo("新的公共规则 {now}");
        consultListener.getValue().receiveConfigInfo("新的咨询规则");
        assertThat(prompts.getChatSystemMessage().get()).isEqualTo("新的公共规则 {now}");
        assertThat(prompts.agentMessage(AgentTypeEnum.CONSULT)).isEqualTo("新的咨询规则");
        commonListener.getValue().receiveConfigInfo(blank);
        consultListener.getValue().receiveConfigInfo(blank);
        assertThat(prompts.getChatSystemMessage().get()).isEqualTo(commonDefault);
        assertThat(prompts.agentMessage(AgentTypeEnum.CONSULT)).isEqualTo(consultDefault);
    }

    @Test
    void nacosFailureRetainsDefaultsAndContinuesLoadingOtherPrompts() throws Exception {
        var manager = mock(NacosConfigManager.class);
        var nacos = mock(ConfigService.class);
        when(manager.getConfigService()).thenReturn(nacos);
        when(nacos.getConfig(eq("system-chat-message.txt"), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("Nacos unavailable"));
        when(nacos.getConfig(eq("agent-route.txt"), anyString(), anyLong())).thenReturn("远程路由规则");
        when(nacos.getConfig(eq("agent-consult.txt"), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("Nacos unavailable"));
        var properties = new AIProperties();
        properties.getSystem().setChat(config("system-chat-message.txt"));
        properties.getSystem().setAgents(Map.of(AgentTypeEnum.ROUTE, config("agent-route.txt"),
                AgentTypeEnum.CONSULT, config("agent-consult.txt")));
        var prompts = new SystemPromptConfig(manager, properties);
        prompts.init();

        assertThat(prompts.getChatSystemMessage().get()).contains("你是天机学堂的 AI 学习助手");
        assertThat(prompts.agentMessage(AgentTypeEnum.CONSULT)).contains("你负责课程咨询");
        assertThat(prompts.agentMessage(AgentTypeEnum.ROUTE)).isEqualTo("远程路由规则");
        assertThat(prompts.agentMessage(AgentTypeEnum.KNOWLEDGE)).contains("你负责知识讲解");
    }

    private AIProperties.System.Chat config(String dataId) {
        var config = new AIProperties.System.Chat();
        config.setDataId(dataId);
        return config;
    }
}
