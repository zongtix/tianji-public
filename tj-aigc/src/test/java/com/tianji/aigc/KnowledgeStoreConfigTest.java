package com.tianji.aigc;

import com.tianji.aigc.config.KnowledgeStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeStoreConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeStoreConfig.class);

    @Test
    void disabledKnowledgeDoesNotRequireCredentialsOrEs() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(EmbeddingModel.class).doesNotHaveBean(VectorStore.class));
    }

    @Test
    void disabledRedisKnowledgeDoesNotCreateConnectionsOrRequireSecrets() {
        runner.withPropertyValues("tj.ai.knowledge.store-type=redis", "tj.ai.knowledge.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("knowledgeVectorJedis")
                        .doesNotHaveBean(VectorStore.class));
    }

    @Test
    void enabledKnowledgeRejectsMissingDashscopeKeyInsteadOfUsingChatCredentials() {
        runner.withPropertyValues("tj.ai.knowledge.enabled=true",
                "tj.ai.knowledge.embedding.api-key=", "tj.ai.knowledge.embedding.base-url=http://127.0.0.1:1",
                "tj.ai.knowledge.embedding.model=text-embedding-v4", "tj.ai.knowledge.embedding.dimensions=1024",
                "spring.ai.openai.api-key=deepseek-must-not-be-used")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage("启用知识库前请设置 DASHSCOPE_API_KEY"));
    }
}
