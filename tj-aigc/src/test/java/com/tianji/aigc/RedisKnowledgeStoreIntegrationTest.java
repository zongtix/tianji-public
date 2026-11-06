package com.tianji.aigc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import com.tianji.aigc.knowledge.KnowledgeDocumentService;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.retry.support.RetryTemplate;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.schemafields.VectorField;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_VECTOR_REDIS_PORT", matches = "36379")
class RedisKnowledgeStoreIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Integer> requestSizes = new CopyOnWriteArrayList<>();
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String index = "tj_aigc_redis_test_" + suffix;
    private final String prefix = "tj:aigc:knowledge:test:" + suffix + ":";
    private HttpServer server;
    private JedisPooled admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = new JedisPooled(new HostAndPort("127.0.0.1", 36379), DefaultJedisClientConfig.builder()
                .user("default").password(System.getenv("TJ_AIGC_VECTOR_REDIS_PASSWORD")).build());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                assertThat(request.path("model").asText()).isEqualTo("text-embedding-v4");
                assertThat(request.path("dimensions").asInt()).isEqualTo(1024);
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-dashscope");
                var input = request.path("input");
                requestSizes.add(input.size());
                assertThat(input.size()).isBetween(1, 10);
                List<Map<String, Object>> data = new ArrayList<>();
                for (int i = 0; i < input.size(); i++) {
                    var vector = new ArrayList<>(Collections.nCopies(1024, 0f));
                    vector.set(0, 1f);
                    data.add(Map.of("object", "embedding", "index", i, "embedding", vector));
                }
                byte[] response = json.writeValueAsBytes(Map.of("object", "list", "data", data,
                        "model", "text-embedding-v4", "usage", Map.of("prompt_tokens", 1, "total_tokens", 1)));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable error) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        try {
            // Only this test's UUID index and documents; never touch production prefixes.
            if (admin.ftList().contains(index)) admin.ftDropIndexDD(index);
        } finally {
            admin.close();
            server.stop(0);
        }
    }

    private ApplicationContextRunner runner() {
        return runner("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-dashscope");
    }

    private ApplicationContextRunner runner(String baseUrl, String apiKey) {
        return new ApplicationContextRunner().withUserConfiguration(KnowledgeStoreConfig.class, KnowledgeDocumentService.class)
                .withPropertyValues("tj.ai.knowledge.enabled=true", "tj.ai.knowledge.store-type=redis",
                        "tj.ai.knowledge.redis.host=127.0.0.1", "tj.ai.knowledge.redis.port=36379",
                        "tj.ai.knowledge.redis.password=" + System.getenv("TJ_AIGC_VECTOR_REDIS_PASSWORD"),
                        "tj.ai.knowledge.redis.index-name=" + index, "tj.ai.knowledge.redis.prefix=" + prefix,
                        "tj.ai.knowledge.embedding.api-key=" + apiKey,
                        "tj.ai.knowledge.embedding.base-url=" + baseUrl,
                        "tj.ai.knowledge.embedding.model=text-embedding-v4", "tj.ai.knowledge.embedding.dimensions=1024");
    }

    @Test
    void redisModeNeedsNoEsAndBatchesEmbeddingsWithScopedMetadataRetrieval() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(VectorStore.class)
                    .doesNotHaveBean("knowledgeVectorStore");
            VectorStore store = context.getBean(VectorStore.class);
            assertThat(store).isInstanceOf(RedisVectorStore.class);
            List<Document> documents = IntStream.range(0, 21).mapToObj(i -> new Document("学习积分片段 " + i,
                    Map.of("documentId", i == 0 ? "allowed-with-hyphens" : "other-document", "title", "积分规则",
                            "chapter", "学习积分", "source", "points.md", "chunkIndex", i + 1,
                            "contentHash", "a".repeat(64), "embeddingModel", "text-embedding-v4"))).toList();
            store.add(documents);
            assertThat(requestSizes.subList(requestSizes.size() - 3, requestSizes.size())).containsExactly(10, 10, 1);
            var hits = store.similaritySearch(SearchRequest.builder().query("学习积分")
                    .filterExpression("documentId in ['allowed-with-hyphens']").topK(3).build());
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).getMetadata()).containsEntry("documentId", "allowed-with-hyphens")
                    .containsEntry("title", "积分规则").containsEntry("chapter", "学习积分")
                    .containsEntry("source", "points.md").containsEntry("embeddingModel", "text-embedding-v4");
            assertThat(hits.get(0).getId()).isEqualTo(documents.get(0).getId());
            var attemptedUnion = new FilterExpressionBuilder().eq("documentId", "allowed-with-hyphens|other-document").build();
            assertThat(store.similaritySearch(SearchRequest.builder().query("学习积分")
                    .filterExpression(attemptedUnion).topK(3).build())).isEmpty();
            assertThat(store.similaritySearch(SearchRequest.builder().query("学习积分")
                    .filterExpression("(documentId in ['allowed-with-hyphens','missing']) && contentHash == '" + "a".repeat(64) + "'")
                    .topK(3).build())).hasSize(1);
        });
    }

    @Test
    void importSkipsRepeatedContentRepairsMissingChunksAndRejectsChangedVersion() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(KnowledgeDocumentService.class);
            var documentIndex = context.getBean(KnowledgeDocumentIndex.class);
            var resource = resource("# 积分\n测试说明。\n## 学习积分\n每天最多50分。\n## 签到\n第七天共11分。");
            var first = service.importMarkdown(resource, "points-with-hyphens", "积分规则");
            assertThat(first.imported()).isTrue();
            assertThat(documentIndex.countChunks("points-with-hyphens", null)).isEqualTo(first.chunks());
            assertThat(documentIndex.listChunks("points-with-hyphens")).hasSize(first.chunks());
            int requests = requestSizes.size();
            assertThat(service.importMarkdown(resource, "points-with-hyphens", "积分规则").imported()).isFalse();
            assertThat(requestSizes).hasSize(requests);
            var chunks = service.readMarkdown(resource, "points-with-hyphens", "积分规则");
            admin.del(prefix + chunks.get(0).getId());
            assertThat(service.importMarkdown(resource, "points-with-hyphens", "积分规则").imported()).isTrue();
            assertThat(documentIndex.countChunks("points-with-hyphens", null)).isEqualTo(first.chunks());
            // 同 contentHash、同数量，仅标题变化：不得静默 skip，须重调 embedding。
            int afterRepair = requestSizes.size();
            assertThat(service.importMarkdown(resource, "points-with-hyphens", "积分规则-改名").imported()).isTrue();
            assertThat(requestSizes.size()).isGreaterThan(afterRepair);
            assertThat(documentIndex.listChunks("points-with-hyphens"))
                    .allMatch(c -> "积分规则-改名".equals(c.title()));
            // 稀疏残留：手动写入同 hash 高 index，再导入应按枚举 ID 删掉而非 count 推导。
            String hash = first.contentHash();
            String sparseId = java.util.UUID.nameUUIDFromBytes(("points-with-hyphens:" + hash + ":50").getBytes(StandardCharsets.UTF_8)).toString();
            context.getBean(VectorStore.class).add(List.of(new Document(sparseId, "残留",
                    Map.of("documentId", "points-with-hyphens", "title", "积分规则-改名", "chapter", "残留",
                            "source", "points.md", "chunkIndex", 50, "contentHash", hash,
                            "embeddingModel", "text-embedding-v4"))));
            assertThat(documentIndex.listChunks("points-with-hyphens").stream().map(KnowledgeDocumentIndex.StoredChunk::id))
                    .contains(sparseId);
            assertThat(service.importMarkdown(resource, "points-with-hyphens", "积分规则-改名").imported()).isTrue();
            assertThat(documentIndex.listChunks("points-with-hyphens").stream().map(KnowledgeDocumentIndex.StoredChunk::id))
                    .doesNotContain(sparseId)
                    .hasSize(first.chunks());
            assertThatThrownBy(() -> service.importMarkdown(resource("# 修改后的规则\n每天500分。"),
                    "points-with-hyphens", "积分规则-改名")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已有其他版本");
            assertThat(documentIndex.countChunks("points-with-hyphens", null)).isEqualTo(first.chunks());
        });
    }

    @Test
    void rejectsWrongDimensionsWithoutReplacingExistingIndex() {
        createIncompatibleIndex(prefix, 3);
        runner().run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("Redis 知识库索引的向量维度或相似度不匹配，请使用新索引，不要覆盖已有数据"));
        assertThat(admin.ftInfo(index).toString()).contains("dim, 3");
        assertThat(requestSizes).isEmpty();
    }

    @Test
    void rejectsWrongPrefixWithoutReplacingExistingIndex() {
        createIncompatibleIndex(prefix + "other:", 1024);
        runner().run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("Redis 知识库索引的类型或 key 前缀不匹配，请使用新索引，不要覆盖已有数据"));
    }

    @Test
    void unavailableRedisFailsRetrievalWithoutInvokingChatModel() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ChatModel model = mock(ChatModel.class);
            var client = ChatClient.builder(model).build();
            context.getBean("knowledgeVectorJedis", JedisPooled.class).close();
            assertThatThrownBy(() -> client.prompt().user("学习积分上限？")
                    .advisors(context.getBean(KnowledgeRetrievalAdvisor.class))
                    .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId in ['points']"))
                    .call().content()).matches(KnowledgeRetrievalAdvisor::isRetrievalFailure);
            verify(model, never()).call(any(Prompt.class));
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_LIVE_RAG", matches = "true")
    void liveDashscopeRedisAndDeepseekStreamingAnswer() {
        runner(System.getenv("DASHSCOPE_BASE_URL"), System.getenv("DASHSCOPE_API_KEY")).run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(KnowledgeDocumentService.class);
            var imported = service.importMarkdown(new ClassPathResource("knowledge/tianji-points.md"),
                    "tianji-points-guide", "天机学堂积分与签到规则");
            assertThat(imported.imported()).isTrue();
            assertThat(service.importMarkdown(new ClassPathResource("knowledge/tianji-points.md"),
                    "tianji-points-guide", "天机学堂积分与签到规则").imported()).isFalse();
            var options = OpenAiChatOptions.builder().model("deepseek-flash")
                    .extraBody(Map.of("thinking", Map.of("type", "disabled"))).build();
            var model = OpenAiChatModel.builder().openAiApi(OpenAiApi.builder()
                            .apiKey(System.getenv("DEEPSEEK_API_KEY")).baseUrl("https://api.deepseek.com").build())
                    .defaultOptions(options).retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            var parts = ChatClient.builder(model).build().prompt()
                    .user("按天机学堂知识库，学习课程一天最多获得多少积分？请简短回答并注明文档标题和章节。")
                    .advisors(context.getBean(KnowledgeRetrievalAdvisor.class))
                    .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId in ['tianji-points-guide']"))
                    .stream().content().collectList().block(Duration.ofSeconds(90));
            assertThat(parts).isNotEmpty();
            String answer = String.join("", parts);
            assertThat(answer).contains("50", "天机学堂积分与签到规则");
            Path report = Path.of("../target/aigc-redis8/integration/live-rag.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "store", "redis", "embeddingModel", "text-embedding-v4", "chatModel", "deepseek-flash",
                    "chunks", imported.chunks(), "deltaCount", parts.size(), "answer", answer,
                    "repeatImportSkipped", true)));
        });
    }

    private void createIncompatibleIndex(String keyPrefix, int dimensions) {
        admin.ftCreate(index, FTCreateParams.createParams().on(IndexDataType.JSON).addPrefix(keyPrefix), List.of(
                VectorField.builder().fieldName("$.embedding").as("embedding")
                        .algorithm(VectorField.VectorAlgorithm.HNSW)
                        .attributes(Map.of("TYPE", "FLOAT32", "DIM", dimensions, "DISTANCE_METRIC", "COSINE")).build()));
    }

    private ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "points.md"; }
        };
    }
}
