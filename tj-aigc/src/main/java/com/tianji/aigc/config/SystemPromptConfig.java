package com.tianji.aigc.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.tianji.aigc.enums.AgentTypeEnum;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Getter
@Configuration
@RequiredArgsConstructor
public class SystemPromptConfig {

    private final NacosConfigManager nacosConfigManager;
    private final AIProperties aiProperties;

    // 使用原子引用，保证线程安全
    private final AtomicReference<String> chatSystemMessage = new AtomicReference<>();
    private final Map<AgentTypeEnum, AtomicReference<String>> agentSystemMessages = new EnumMap<>(AgentTypeEnum.class);
    private String courseCardContextMessage;

    @PostConstruct
    public void init() {
        String common = readDefault("common-system-message.txt");
        chatSystemMessage.set(common);
        courseCardContextMessage = readDefault("course-card-context.txt");
        var system = aiProperties.getSystem();
        loadConfig(system.getChat(), chatSystemMessage, common);
        for (AgentTypeEnum type : AgentTypeEnum.values()) {
            String fallback = readDefault(type.name().toLowerCase(Locale.ROOT) + "-system-message.txt");
            var message = new AtomicReference<>(fallback);
            agentSystemMessages.put(type, message);
            loadConfig(system.getAgents().get(type), message, fallback);
        }
    }

    public String agentMessage(AgentTypeEnum type) {
        return agentSystemMessages.get(type).get();
    }

    private String readDefault(String filename) {
        String path = "prompts/" + filename;
        try {
            String message = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            if (!StringUtils.hasText(message)) {
                throw new IllegalStateException("默认提示词文件不能为空：" + path);
            }
            return message;
        } catch (IOException error) {
            throw new IllegalStateException("无法读取默认提示词文件：" + path, error);
        }
    }

    private void loadConfig(AIProperties.System.Chat chatConfig, AtomicReference<String> target, String fallback) {
        if (chatConfig == null || !StringUtils.hasText(chatConfig.getDataId())) return;
        try {
            var dataId = chatConfig.getDataId();
            var group = chatConfig.getGroup();
            var timeoutMs = chatConfig.getTimeoutMs();

            var config = nacosConfigManager.getConfigService().getConfig(dataId, group, timeoutMs);
            target.set(StringUtils.hasText(config) ? config : fallback);
            log.info("提示词已加载：dataId={}, group={}, source={}", dataId, group,
                    StringUtils.hasText(config) ? "Nacos" : "classpath");

            nacosConfigManager.getConfigService().addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String info) {
                    target.set(StringUtils.hasText(info) ? info : fallback);
                    log.info("提示词已更新：dataId={}, group={}, source={}", dataId, group,
                            StringUtils.hasText(info) ? "Nacos" : "classpath");
                }
            });
        } catch (Exception e) {
            log.warn("加载 Nacos 提示词失败，保留当前提示词：dataId={}, group={}",
                    chatConfig.getDataId(), chatConfig.getGroup(), e);
        }
    }

}
