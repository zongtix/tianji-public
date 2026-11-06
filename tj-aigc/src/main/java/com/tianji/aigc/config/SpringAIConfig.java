package com.tianji.aigc.config;

import com.tianji.aigc.agent.ChatContextBudget;
import com.tianji.aigc.agent.ChatContextBudgetAdvisor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import com.tianji.aigc.tools.CourseToolCallLimitException;
import com.tianji.aigc.tools.CourseToolPreparationAdvisor;
import com.tianji.aigc.tools.PublicRuleCallLimitException;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class SpringAIConfig {

    @Value("${tj.ai.memory.max:100}")
    private Integer maxMessages;

    @Value("${tj.ai.long-term-memory.enabled:false}")
    private boolean userMemoryEnabled;

    @Value("${tj.ai.memory.window-tokens:16384}")
    private int windowTokens = 16384;
    @Value("${tj.ai.memory.output-reserve:2048}")
    private int outputReserve = 2048;
    @Value("${tj.ai.memory.safety-margin:512}")
    private int safetyMargin = 512;

    @Bean
    public ChatContextBudget chatContextBudget() {
        return new ChatContextBudget(windowTokens, outputReserve, safetyMargin);
    }

    /**
     * 配置 ChatClient
     */
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 Advisor loggerAdvisor, // 日志记录器
                                 Advisor messageChatMemoryAdvisor, ChatModel chatModel) {
        return chatClient(chatClientBuilder, loggerAdvisor, messageChatMemoryAdvisor, chatModel, ObservationRegistry.NOOP);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, Advisor loggerAdvisor,
                                 Advisor messageChatMemoryAdvisor, ChatModel chatModel, ObservationRegistry registry) {
        var budget = chatContextBudget();
        var budgetAdvisor = new ChatContextBudgetAdvisor(budget);
        if (userMemoryEnabled) {
            return chatClientBuilder.defaultAdvisors(messageChatMemoryAdvisor, budgetAdvisor,
                    new CourseToolPreparationAdvisor(chatModel, registry, budget)).build();
        }
        return chatClientBuilder.defaultAdvisors(loggerAdvisor, messageChatMemoryAdvisor, budgetAdvisor,
                new CourseToolPreparationAdvisor(chatModel, registry, budget)).build();
    }

    /**
     * 覆盖 Spring AI 默认处理器：公共规则/课程工具预算耗尽必须重抛以打断 OpenAiChatModel 工具递归；
     * 其它工具异常仍转为结果字符串，保持原语义。
     */
    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor(
            @Value("${spring.ai.tools.throw-exception-on-error:false}") boolean throwExceptionOnError) {
        List<Class<? extends RuntimeException>> rethrown = new ArrayList<>();
        rethrown.add(PublicRuleCallLimitException.class);
        rethrown.add(CourseToolCallLimitException.class);
        Class<? extends RuntimeException> oauth2 = oauth2ClientAuthorizationException();
        if (oauth2 != null) rethrown.add(oauth2);
        return DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(throwExceptionOnError)
                .rethrowExceptions(rethrown).build();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends RuntimeException> oauth2ClientAuthorizationException() {
        try {
            Class<?> type = ClassUtils.forName(
                    "org.springframework.security.oauth2.client.ClientAuthorizationException", null);
            if (RuntimeException.class.isAssignableFrom(type)) {
                return (Class<? extends RuntimeException>) type;
            }
        } catch (ClassNotFoundException ignored) {
            // optional classpath
        }
        return null;
    }

    /**
     * 日志记录器
     */
    @Bean
    public Advisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository){
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(this.maxMessages)
                .build();
    }

    @Bean
    public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory){
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
