package com.tianji.aigc;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tianji.aigc.config.KnowledgeRagProperties;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KnowledgeRagValidationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @ParameterizedTest
    @EnumSource(KnowledgeRagProperties.Mode.class)
    void elasticsearchSupportsEveryModeWithoutRequiringRerankCredentials(KnowledgeRagProperties.Mode mode) {
        var vectors = mock(ElasticsearchVectorStore.class);
        pipeline(vectors, mode).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeRetrievalPipeline.class);
            verify(vectors, never()).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
        });
    }

    @ParameterizedTest
    @EnumSource(value = KnowledgeRagProperties.Mode.class, names = {"A", "APRIME"})
    void redisSupportsBothVectorModes(KnowledgeRagProperties.Mode mode) {
        var vectors = mock(RedisVectorStore.class);
        pipeline(vectors, mode).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeRetrievalPipeline.class);
            verify(vectors, never()).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
        });
    }

    @ParameterizedTest
    @EnumSource(value = KnowledgeRagProperties.Mode.class, names = {"B", "C", "D", "B_CONTEXT"})
    void redisRejectsHybridModesAtStartup(KnowledgeRagProperties.Mode mode) {
        var vectors = mock(RedisVectorStore.class);
        pipeline(vectors, mode).run(context -> {
            assertThat(context).hasFailed().getFailure()
                    .hasRootCauseMessage("混合检索模式 B/C/D/B_CONTEXT 需要 Elasticsearch 知识库存储；Redis 仅支持 A/APRIME");
            verify(vectors, never()).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
        });
    }

    @ParameterizedTest
    @CsvSource({
            "retrieval.tuned-top-k,0", "retrieval.tuned-top-k,-1",
            "retrieval.keyword-top-k,0", "retrieval.keyword-top-k,-1",
            "retrieval.context-top-k,0", "retrieval.context-top-k,-1",
            "retrieval.tuned-threshold,-0.01", "retrieval.tuned-threshold,1.01", "retrieval.tuned-threshold,NaN",
            "retrieval.rank-constant,0", "retrieval.rank-constant,-1",
            "retrieval.fusion-limit,0", "retrieval.fusion-limit,-1",
            "preparation-timeout,0s", "preparation-timeout,-1s",
            "rerank.timeout,0s", "rerank.timeout,-1s",
            "rerank.input-limit,0", "rerank.input-limit,-1",
            "rerank.output-limit,0", "rerank.output-limit,-1",
            "rerank.max-item-tokens,0", "rerank.max-item-tokens,-1",
            "rerank.max-request-tokens,0", "rerank.max-request-tokens,-1",
            "context.window-tokens,0", "context.window-tokens,-1",
            "context.output-reserve,-1", "context.safety-margin,-1"
    })
    void rejectsInvalidNestedSettingsAtBinding(String name, String value) {
        runner.withPropertyValues("tj.ai.knowledge.rag." + name + "=" + value)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @ParameterizedTest
    @CsvSource({"2560,2048,512", "2559,2048,512", "2147483647,2147483647,2147483647"})
    void windowMustLeaveRoomBeyondReservedBudget(int window, int output, int safety) {
        runner.withPropertyValues("tj.ai.knowledge.rag.context.window-tokens=" + window,
                "tj.ai.knowledge.rag.context.output-reserve=" + output,
                "tj.ai.knowledge.rag.context.safety-margin=" + safety)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @ParameterizedTest
    @CsvSource({"0,0,0", "1,2048,0", "0.45,2048,512"})
    void acceptsThresholdBoundariesAndOptionalReserves(double threshold, int output, int safety) {
        runner.withPropertyValues("tj.ai.knowledge.rag.retrieval.tuned-threshold=" + threshold,
                "tj.ai.knowledge.rag.context.output-reserve=" + output,
                "tj.ai.knowledge.rag.context.safety-margin=" + safety)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void defaultsAndEnabledButUnconfiguredRerankCanStillStart() {
        runner.run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("tj.ai.knowledge.rag.rerank.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void validationErrorsAndPropertiesToStringDoNotExposeApiKey(CapturedOutput output) {
        String secret = "sk-validation-secret-sentinel";
        runner.withPropertyValues("tj.ai.knowledge.rag.rerank.api-key=" + secret,
                "tj.ai.knowledge.rag.rerank.timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("timeout")
                            .hasStackTraceContaining("BindValidationException");
                    assertThat(org.assertj.core.util.Throwables.getStackTrace(context.getStartupFailure())).doesNotContain(secret);
                });
        runner.withPropertyValues("tj.ai.knowledge.rag.rerank.api-key=" + secret)
                .run(context -> assertThat(context.getBean(KnowledgeRagProperties.class).toString()).doesNotContain(secret));
        assertThat(output).doesNotContain(secret);
    }

    private ApplicationContextRunner pipeline(VectorStore vectors, KnowledgeRagProperties.Mode mode) {
        return runner.withUserConfiguration(PipelineConfiguration.class)
                .withBean(VectorStore.class, () -> vectors)
                .withPropertyValues("tj.ai.knowledge.rag.mode=" + mode);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KnowledgeRagProperties.class)
    static class PropertiesConfiguration { }

    @Configuration(proxyBeanMethods = false)
    static class PipelineConfiguration {
        @Bean
        KnowledgeRetrievalPipeline pipeline(VectorStore vectors, ObjectProvider<ElasticsearchClient> clients,
                ObjectProvider<ObservationRegistry> observations, KnowledgeRagProperties settings) {
            // 仅装配真实生产工厂的检索 Bean，其余网络依赖均不注册。
            return new KnowledgeStoreConfig().knowledgeRetrievalPipeline(vectors, 3, 0.6,
                    "tj_aigc_validation", clients, observations, settings);
        }
    }
}
