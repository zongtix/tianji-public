package com.tianji.aigc.config;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.tianji.aigc.mapper.ChatMemoryMessageMapper;
import com.tianji.aigc.longterm.UserMemoryStore;
import com.tianji.aigc.memory.MongoChatMemoryRepository;
import com.tianji.aigc.memory.MysqlChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatMemoryStorageProperties.class)
public class ChatMemoryStorageConfig {
    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.memory", name = "type", havingValue = "mysql", matchIfMissing = true)
    public ChatMemoryRepository mysqlChatMemoryRepository(ChatMemoryMessageMapper mapper,
                                                         PlatformTransactionManager transactionManager,
                                                         ObjectProvider<UserMemoryStore> userMemory) {
        return new MysqlChatMemoryRepository(mapper, transactionManager, userMemory.getIfAvailable());
    }

    /** 只在选择 MongoDB 时创建客户端；MySQL 模式无需 MongoDB 配置或服务。 */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "tj.ai.memory", name = "type", havingValue = "mongodb")
    static class MongoStorageConfig {
        @Bean(destroyMethod = "close")
        MongoClient chatMemoryMongoClient(@Value("${spring.data.mongodb.uri}") String uri) {
            return MongoClients.create(uri);
        }

        @Bean
        MongoTemplate chatMemoryMongoTemplate(MongoClient client,
                                             @Value("${spring.data.mongodb.uri}") String uri) {
            String database = new ConnectionString(uri).getDatabase();
            Assert.hasText(database, "MongoDB URI 必须指定会话记忆数据库");
            return new MongoTemplate(client, database);
        }

        @Bean
        ChatMemoryRepository mongoChatMemoryRepository(MongoTemplate template) {
            return new MongoChatMemoryRepository(template);
        }
    }
}
