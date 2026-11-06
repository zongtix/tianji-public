package com.tianji.aigc.config;

import com.tianji.aigc.longterm.UserMemoryCipher;
import com.tianji.aigc.longterm.UserMemoryExtractor;
import com.tianji.aigc.longterm.UserMemoryService;
import com.tianji.aigc.longterm.UserMemoryStore;
import com.tianji.aigc.longterm.UserMemoryWorker;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(UserMemoryKeyProperties.class)
@ConditionalOnProperty(prefix = "tj.ai.long-term-memory", name = "enabled", havingValue = "true")
public class UserMemoryConfig {
    @Bean
    public UserMemoryCipher userMemoryCipher(
            @Value("${tj.ai.long-term-memory.encryption-key:}") String key,
            @Value("${tj.ai.memory.type:mysql}") String chatMemoryType,
            @Value("${spring.ai.chat.observations.log-prompt:false}") boolean logPrompt,
            @Value("${spring.ai.chat.observations.log-completion:false}") boolean logCompletion,
            UserMemoryKeyProperties keys) {
        Assert.state("mysql".equals(chatMemoryType), "长期记忆 V1 需要 MySQL 会话记忆");
        Assert.state(!logPrompt && !logCompletion, "长期记忆启用前必须关闭模型 prompt/completion 内容采集");
        return new UserMemoryCipher(key, keys.getDecryptionKeys());
    }

    @Bean
    public UserMemoryStore userMemoryStore(JdbcTemplate jdbc, PlatformTransactionManager manager, UserMemoryCipher cipher) {
        return new UserMemoryStore(jdbc, manager, cipher);
    }

    @Bean
    public UserMemoryService userMemoryService(UserMemoryStore store, ChatMemory memory) {
        return new UserMemoryService(store, memory);
    }

    @Bean
    public UserMemoryExtractor userMemoryExtractor(@Lazy ChatModel model) { return new UserMemoryExtractor(model); }

    @Bean(destroyMethod = "close")
    public UserMemoryWorker userMemoryWorker(UserMemoryStore store, UserMemoryExtractor extractor, ObservationRegistry observations) {
        return new UserMemoryWorker(store, extractor, observations);
    }
}
