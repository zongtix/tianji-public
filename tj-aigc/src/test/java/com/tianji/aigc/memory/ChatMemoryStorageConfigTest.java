package com.tianji.aigc.memory;

import com.mongodb.client.MongoClient;
import com.tianji.aigc.config.ChatMemoryStorageConfig;
import com.tianji.aigc.mapper.ChatMemoryMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatMemoryStorageConfigTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(ChatMemoryStorageConfig.class)
            .withBean(ChatMemoryMessageMapper.class, () -> mock(ChatMemoryMessageMapper.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void defaultsToMysqlWithoutMongoUriOrClient() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ChatMemoryRepository.class)
                    .doesNotHaveBean(MongoClient.class).doesNotHaveBean(MongoTemplate.class);
            assertThat(context.getBean(ChatMemoryRepository.class)).isInstanceOf(MysqlChatMemoryRepository.class);
        });
    }

    @Test
    void explicitMysqlDoesNotResolveMongoUri() {
        contexts.withPropertyValues("tj.ai.memory.type=mysql", "spring.data.mongodb.uri=${MISSING_MONGO_URI}")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatMemoryRepository.class)
                        .doesNotHaveBean(MongoClient.class));
    }

    @Test
    void mongodbSelectsOnlyMongoRepository() {
        contexts.withPropertyValues("tj.ai.memory.type=mongodb",
                "spring.data.mongodb.uri=mongodb://127.0.0.1:1/tj_aigc?serverSelectionTimeoutMS=100")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ChatMemoryRepository.class)
                            .hasSingleBean(MongoClient.class).hasSingleBean(MongoTemplate.class);
                    assertThat(context.getBean(ChatMemoryRepository.class)).isInstanceOf(MongoChatMemoryRepository.class);
                });
    }

    @Test
    void unknownStorageFailsInsteadOfFallingBackToVolatileMemory() {
        contexts.withPropertyValues("tj.ai.memory.type=typo")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void mongodbRequiresAnExplicitUri() {
        contexts.withPropertyValues("tj.ai.memory.type=mongodb")
                .run(context -> assertThat(context).hasFailed());
    }
}
