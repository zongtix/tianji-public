package com.tianji.aigc.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.knowledge.KnowledgeAnswerPrompt;
import com.tianji.aigc.knowledge.KnowledgeQueryRewriter;
import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import com.tianji.aigc.knowledge.RedisKnowledgeDocumentIndex;
import com.tianji.aigc.knowledge.RedisKnowledgeVectorStore;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.observation.ObservationRegistry;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.KnowledgeContextSelector;
import com.tianji.aigc.knowledge.ElasticsearchExactMetadataFilterConverter;
import com.tianji.aigc.knowledge.KnowledgeKeywordSearch;
import com.tianji.aigc.knowledge.KnowledgeReranker;
import com.tianji.aigc.knowledge.RagTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 百炼只负责向量化；聊天继续使用原来的 DeepSeek 配置。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "enabled", havingValue = "true")
@Import(KnowledgeStoreConfig.RedisStorageConfig.class)
@EnableConfigurationProperties(KnowledgeRagProperties.class)
public class KnowledgeStoreConfig {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeStoreConfig.class);
    /** Embedding 单路读超时；须落在 preparation 预算内（默认 25s），不依赖 interrupt 是否生效。 */
    static final Duration EMBEDDING_READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public EmbeddingModel dashscopeEmbeddingModel(
            @Value("${tj.ai.knowledge.embedding.api-key}") String apiKey,
            @Value("${tj.ai.knowledge.embedding.base-url}") String baseUrl,
            @Value("${tj.ai.knowledge.embedding.model}") String model,
            @Value("${tj.ai.knowledge.embedding.dimensions}") int dimensions,
            ObjectProvider<ObservationRegistry> observations) {
        Assert.hasText(apiKey, "启用知识库前请设置 DASHSCOPE_API_KEY");
        Assert.hasText(baseUrl, "启用知识库前请设置 DASHSCOPE_BASE_URL（OpenAI 兼容地址）");
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        // JDK HttpClient 阻塞调用对 interrupt 的响应不保证；不能用 60s>25s 断言“必然再跑 35s”。
        // 读超时必须落在单路 preparation 预算内，避免超时后底层仍长时间占用 boundedElastic。
        requestFactory.setReadTimeout(EMBEDDING_READ_TIMEOUT);
        var api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl)
                .embeddingsPath("/embeddings")
                .restClientBuilder(org.springframework.web.client.RestClient.builder().requestFactory(requestFactory))
                .build();
        var options = OpenAiEmbeddingOptions.builder().model(model).dimensions(dimensions)
                .encodingFormat("float").build();
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE, options,
                RetryTemplate.builder().maxAttempts(1).build(), observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "store-type", havingValue = "elasticsearch", matchIfMissing = true)
    public ElasticsearchVectorStore knowledgeVectorStore(RestClient restClient, ElasticsearchClient client,
            EmbeddingModel embeddingModel,
            @Value("${tj.ai.knowledge.index-name}") String index,
            @Value("${tj.ai.knowledge.embedding.dimensions}") int dimensions,
            ObjectProvider<ObservationRegistry> observations) throws IOException {
        ensureElasticsearchKnowledgeIndex(client, index, dimensions);
        var options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(index);
        options.setDimensions(dimensions);
        options.setSimilarity(SimilarityFunction.cosine);
        return ElasticsearchVectorStore.builder(restClient, embeddingModel).options(options)
                .initializeSchema(false)
                .filterExpressionConverter(new ElasticsearchExactMetadataFilterConverter())
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .batchingStrategy(embeddingBatchingStrategy()).build();
    }

    /**
     * 先 exists 再 create 非原子：多实例首启可能撞 resource_already_exists。
     * 仅吞该冲突并继续校验 mapping；其余创建错误仍失败。
     */
    static void ensureElasticsearchKnowledgeIndex(ElasticsearchClient client, String index, int dimensions)
            throws IOException {
        Assert.isTrue(index.startsWith("tj_aigc_"), "知识库索引必须使用 tj_aigc_ 前缀");
        if (!client.indices().exists(e -> e.index(index)).value()) {
            try {
                client.indices().create(c -> c.index(index)
                        .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                        .mappings(m -> m.properties("id", p -> p.keyword(k -> k))
                                .properties("content", p -> p.text(t -> t))
                                .properties("metadata", p -> p.object(o -> o))
                                .properties("embedding", p -> p.denseVector(v -> v.dims(dimensions).index(true)
                                        .similarity(DenseVectorSimilarity.Cosine)))));
            } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException conflict) {
                if (!isResourceAlreadyExists(conflict)) {
                    throw conflict;
                }
            }
        }
        assertElasticsearchEmbeddingMapping(client, index, dimensions);
    }

    static boolean isResourceAlreadyExists(co.elastic.clients.elasticsearch._types.ElasticsearchException error) {
        for (var cause = error.error(); cause != null; cause = cause.causedBy()) {
            if ("resource_already_exists_exception".equals(cause.type())) {
                return true;
            }
        }
        return false;
    }

    static void assertElasticsearchEmbeddingMapping(ElasticsearchClient client, String index, int dimensions)
            throws IOException {
        var field = client.indices().getMapping(g -> g.index(index)).get(index)
                .mappings().properties().get("embedding");
        Assert.isTrue(field != null && field.isDenseVector()
                        && Integer.valueOf(dimensions).equals(field.denseVector().dims())
                        && field.denseVector().similarity() == DenseVectorSimilarity.Cosine,
                "知识库索引的向量维度或相似度不匹配，请使用新的索引，不要覆盖已有数据");
    }

    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "store-type", havingValue = "elasticsearch", matchIfMissing = true)
    public KnowledgeDocumentIndex elasticsearchDocumentIndex(ElasticsearchVectorStore vectorStore,
            ElasticsearchClient client, @Value("${tj.ai.knowledge.index-name}") String index) {
        return new KnowledgeDocumentIndex() {
            @Override
            public long countChunks(String documentId, String excludedHash) throws IOException {
                return client.count(c -> c.index(index).query(q -> q.bool(b -> {
                    b.filter(f -> f.term(t -> t.field("metadata.documentId.keyword").value(documentId)));
                    if (excludedHash != null) {
                        b.mustNot(f -> f.term(t -> t.field("metadata.contentHash.keyword").value(excludedHash)));
                    }
                    return b;
                }))).count();
            }

            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public List<StoredChunk> listChunks(String documentId) throws IOException {
                Assert.isTrue(documentId != null && documentId.matches("[a-zA-Z0-9_-]+"), "文档 ID 格式不正确");
                var response = client.search(s -> s.index(index).size(MAX_LISTED_CHUNKS)
                                .query(q -> q.term(t -> t.field("metadata.documentId.keyword").value(documentId)))
                                .source(src -> src.filter(f -> f.includes("content",
                                        "metadata.contentHash", "metadata.title", "metadata.chapter",
                                        "metadata.embeddingModel", "metadata.chunkIndex")
                                        .excludes("embedding"))),
                        Map.class);
                Assert.state(!response.timedOut() && response.shards().failed().intValue() == 0,
                        "文档片段枚举返回不完整，拒绝自动布局核对与残留清理");
                var hits = response.hits().hits();
                long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
                Assert.isTrue(total <= MAX_LISTED_CHUNKS && hits.size() == total,
                        "文档片段数超过安全上限，拒绝自动布局核对与残留清理");
                List<StoredChunk> chunks = new ArrayList<>(hits.size());
                for (var hit : hits) {
                    Map<String, Object> source = hit.source() == null ? Map.of() : hit.source();
                    Map<String, Object> metadata = source.get("metadata") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map : Map.of();
                    Object id = hit.id();
                    chunks.add(new StoredChunk(
                            id == null ? null : String.valueOf(id),
                            stringMeta(metadata.get("contentHash")),
                            stringMeta(metadata.get("title")),
                            stringMeta(metadata.get("chapter")),
                            stringMeta(metadata.get("embeddingModel")),
                            metadata.get("chunkIndex"), stringMeta(source.get("content"))));
                }
                return List.copyOf(chunks);
            }

            private static String stringMeta(Object value) {
                if (value == null) {
                    return null;
                }
                String text = String.valueOf(value);
                return text.isBlank() ? null : text;
            }
        };
    }

    @ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "store-type", havingValue = "redis")
    @EnableConfigurationProperties(KnowledgeRedisProperties.class)
    static class RedisStorageConfig {
        @Bean(destroyMethod = "close")
        public JedisPooled knowledgeVectorJedis(KnowledgeRedisProperties properties) {
            var config = DefaultJedisClientConfig.builder().user(properties.getUsername())
                    .password(properties.getPassword()).database(0)
                    .connectionTimeoutMillis(3000).socketTimeoutMillis(10000).build();
            return new JedisPooled(new HostAndPort(properties.getHost(), properties.getPort()), config);
        }

        @Bean
        public RedisVectorStore redisKnowledgeVectorStore(
                @Qualifier("knowledgeVectorJedis") JedisPooled jedis, EmbeddingModel embeddingModel,
                KnowledgeRedisProperties properties, ObjectProvider<ObservationRegistry> observations) {
            return new RedisKnowledgeVectorStore(RedisVectorStore.builder(jedis, embeddingModel)
                    .indexName(properties.getIndexName()).prefix(properties.getPrefix())
                    .vectorAlgorithm(RedisVectorStore.Algorithm.HNSW)
                    .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                    .metadataFields(MetadataField.tag("documentId"), MetadataField.tag("contentHash"),
                            MetadataField.text("title"), MetadataField.text("chapter"),
                            MetadataField.tag("source"), MetadataField.numeric("chunkIndex"),
                            MetadataField.tag("embeddingModel"))
                    .initializeSchema(true).batchingStrategy(embeddingBatchingStrategy()));
        }

        @Bean
        public KnowledgeDocumentIndex redisDocumentIndex(RedisVectorStore vectorStore,
                @Qualifier("knowledgeVectorJedis") JedisPooled jedis, KnowledgeRedisProperties properties,
                @Value("${tj.ai.knowledge.embedding.dimensions}") int dimensions) {
            var index = new RedisKnowledgeDocumentIndex(jedis, properties.getIndexName(), properties.getPrefix());
            index.validateSchema(properties.getPrefix(), dimensions);
            return index;
        }
    }

    private static BatchingStrategy embeddingBatchingStrategy() {
        var tokenBatching = new TokenCountBatchingStrategy();
        return documents -> {
            List<List<Document>> batches = new ArrayList<>();
            for (List<Document> tokenBatch : tokenBatching.batch(documents)) {
                // text-embedding-v4 的单次请求最多接受 10 条文本。
                for (int offset = 0; offset < tokenBatch.size(); offset += 10) {
                    batches.add(tokenBatch.subList(offset, Math.min(offset + 10, tokenBatch.size())));
                }
            }
            return batches;
        };
    }

    @Bean
    public KnowledgeRetrievalPipeline knowledgeRetrievalPipeline(VectorStore vectorStore,
            @Value("${tj.ai.knowledge.retrieval.top-k:3}") int topK,
            @Value("${tj.ai.knowledge.retrieval.similarity-threshold:0.6}") double threshold,
            @Value("${tj.ai.knowledge.index-name:tj_aigc_knowledge_v4_1024}") String index,
            ObjectProvider<ElasticsearchClient> clients, ObjectProvider<ObservationRegistry> observations,
            KnowledgeRagProperties settings) {
        // 与 KnowledgeRetrievalPipeline 的 hybrid 判定一致，启动时拒绝不兼容的存储组合。
        Assert.isTrue(vectorStore instanceof ElasticsearchVectorStore
                        || settings.getMode() == KnowledgeRagProperties.Mode.A
                        || settings.getMode() == KnowledgeRagProperties.Mode.APRIME,
                "混合检索模式 B/C/D/B_CONTEXT 需要 Elasticsearch 知识库存储；Redis 仅支持 A/APRIME");
        var search = SearchRequest.builder().topK(topK).similarityThreshold(threshold).build();
        var telemetry = new RagTelemetry(observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        var selector = new KnowledgeContextSelector(settings.getContext());
        // 延迟解析 ES，独立导入和 Redis 的 A/APRIME 链路不依赖关键词客户端。
        var keywords = new KnowledgeKeywordSearch(() -> {
            Assert.state(vectorStore instanceof ElasticsearchVectorStore, "混合检索需要 Elasticsearch 知识库存储");
            return clients.getObject();
        }, index);
        var pipeline = new KnowledgeRetrievalPipeline(vectorStore, keywords,
                new KnowledgeReranker(settings.getRerank(), selector), search, settings, telemetry);
        log.info("Knowledge RAG initialized: mode={}, promptVersion={}, rerankEnabled={}",
                settings.getMode(), settings.getPromptVersion(), settings.getRerank().isEnabled());
        return pipeline;
    }

    @Bean
    public KnowledgeRetrievalAdvisor knowledgeAdvisor(VectorStore vectorStore, @Lazy ChatModel chatModel,
            KnowledgeRetrievalPipeline pipeline, KnowledgeRagProperties settings) {
        // 独立导入上下文不需要聊天模型，只有实际改写时才解析此依赖。
        // Advisor order=0：记忆 Advisor 先保存原始问题，再改写检索文本并增强本次请求。
        var selector = new KnowledgeContextSelector(settings.getContext());
        return new KnowledgeRetrievalAdvisor(vectorStore, pipeline.baseline(),
                KnowledgeAnswerPrompt.create(settings.getPromptVersion()),
                new KnowledgeQueryRewriter(chatModel), pipeline, selector, settings, pipeline.telemetry());
    }

}
