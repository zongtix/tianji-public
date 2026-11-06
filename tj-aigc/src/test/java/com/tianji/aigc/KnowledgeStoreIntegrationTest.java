package com.tianji.aigc;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_ES_PORT", matches = "19201")
class KnowledgeStoreIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    private ApplicationContextRunner runner(String index, String baseUrl, String apiKey) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class))
                .withUserConfiguration(KnowledgeStoreConfig.class)
                .withPropertyValues("spring.elasticsearch.uris=http://127.0.0.1:19201",
                        "spring.elasticsearch.username=tj_aigc_app",
                        "spring.elasticsearch.password=" + System.getenv("TJ_AIGC_ES_PASSWORD"),
                        "tj.ai.knowledge.enabled=true", "tj.ai.knowledge.index-name=" + index,
                        "tj.ai.knowledge.embedding.api-key=" + apiKey,
                        "tj.ai.knowledge.embedding.base-url=" + baseUrl,
                        "tj.ai.knowledge.embedding.model=text-embedding-v4",
                        "tj.ai.knowledge.embedding.dimensions=1024");
    }

    @Test
    void realVectorStoreBatchesProviderRequestsAndRetrievesWithMetadata() throws Exception {
        List<Integer> requestSizes = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/embeddings", exchange -> {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-dashscope");
                assertThat(request.path("model").asText()).isEqualTo("text-embedding-v4");
                assertThat(request.path("dimensions").asInt()).isEqualTo(1024);
                assertThat(request.path("encoding_format").asText()).isEqualTo("float");
                JsonNode input = request.path("input");
                requestSizes.add(input.size());
                assertThat(input.size()).isBetween(1, 10);
                List<Map<String, Object>> data = new ArrayList<>();
                for (int i = 0; i < input.size(); i++) {
                    List<Float> vector = new ArrayList<>(Collections.nCopies(1024, 0f));
                    vector.set(input.get(i).asText().contains("优惠券") ? 0 : 1, 1f);
                    data.add(Map.of("object", "embedding", "index", i, "embedding", vector));
                }
                byte[] response = json.writeValueAsBytes(Map.of("object", "list", "data", data,
                        "model", "text-embedding-v4", "usage", Map.of("prompt_tokens", 1, "total_tokens", 1)));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        String index = "tj_aigc_vector_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            runner(index, "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1", "test-dashscope")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(VectorStore.class);
                        VectorStore store = context.getBean(VectorStore.class);
                        ElasticsearchClient client = context.getBean(ElasticsearchClient.class);
                        try {
                            List<Document> docs = IntStream.range(0, 21).mapToObj(i -> new Document(
                                    "优惠券兑换知识片段 " + i, Map.of("courseId", "coupon", "documentId", i == 0 ? "allowed" : "other"))).toList();
                            store.add(docs);
                            awaitCount(client, index, 21);
                            // Spring AI may make a one-text dimension probe before the document batches.
                            assertThat(requestSizes).allSatisfy(size -> assertThat(size).isBetween(1, 10));
                            assertThat(requestSizes.subList(requestSizes.size() - 3, requestSizes.size()))
                                    .containsExactly(10, 10, 1);
                            var hits = store.similaritySearch(SearchRequest.builder().query("如何兑换优惠券？")
                                    .topK(2).filterExpression("courseId == 'coupon'").build());
                            assertThat(hits).hasSize(2).allSatisfy(doc -> {
                                assertThat(doc.getText()).contains("优惠券");
                                assertThat(doc.getMetadata()).containsEntry("courseId", "coupon");
                            });
                            var scoped = store.similaritySearch(SearchRequest.builder().query("如何兑换优惠券？")
                                    .topK(3).filterExpression("documentId in ['allowed']").build());
                            assertThat(scoped).hasSize(1);
                            assertThat(scoped.get(0).getMetadata()).containsEntry("documentId", "allowed");
                            var keyword = new com.tianji.aigc.knowledge.KnowledgeKeywordSearch(client, index);
                            var parser = new org.springframework.ai.vectorstore.filter.FilterExpressionTextParser();
                            var lexical = keyword.search("优惠券", parser.parse("documentId in ['allowed']"), 3);
                            assertThat(lexical).hasSize(1);
                            assertThat(lexical.get(0).getId()).isEqualTo(scoped.get(0).getId());
                            assertThat(keyword.search("优惠券", parser.parse("documentId in ['unpublished']"), 3)).isEmpty();
                            store.delete(docs.stream().map(Document::getId).toList());
                            awaitCount(client, index, 0);
                        } finally {
                            client.indices().delete(d -> d.index(index));
                        }
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesToReuseAnIndexWithDifferentVectorDimensions() {
        String index = "tj_aigc_dimension_test_" + UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class))
                .withPropertyValues("spring.elasticsearch.uris=http://127.0.0.1:19201",
                        "spring.elasticsearch.username=tj_aigc_app",
                        "spring.elasticsearch.password=" + System.getenv("TJ_AIGC_ES_PASSWORD"))
                .run(context -> {
                    ElasticsearchClient client = context.getBean(ElasticsearchClient.class);
                    client.indices().create(c -> c.index(index)
                            .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                            .mappings(m -> m.properties("embedding", p -> p.denseVector(v -> v.dims(3)))));
                    try {
                        runner(index, "http://127.0.0.1:1", "test-dashscope")
                                .run(failed -> assertThat(failed).hasFailed().getFailure()
                                        .hasRootCauseMessage("知识库索引的向量维度或相似度不匹配，请使用新的索引，不要覆盖已有数据"));
                        assertThat(client.indices().getMapping(g -> g.index(index)).get(index)
                                .mappings().properties().get("embedding").denseVector().dims()).isEqualTo(3);
                    } finally {
                        client.indices().delete(d -> d.index(index));
                    }
                });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_DASHSCOPE", matches = "true")
    void liveDashscopeEmbeddingsRoundTripThroughEs() {
        String index = "tj_aigc_live_test_" + UUID.randomUUID().toString().replace("-", "");
        runner(index, System.getenv("DASHSCOPE_BASE_URL"), System.getenv("DASHSCOPE_API_KEY"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    VectorStore store = context.getBean(VectorStore.class);
                    ElasticsearchClient client = context.getBean(ElasticsearchClient.class);
                    try {
                        store.add(List.of(new Document("优惠券兑换码过期后不能兑换，需要在有效期内完成兑换。", Map.of("documentId", "live-allowed")),
                                new Document("Redis 可以缓存数据，减少对数据库的访问。", Map.of("documentId", "live-other"))));
                        awaitCount(client, index, 2);
                        var hits = store.similaritySearch(SearchRequest.builder().query("兑换码过期了还能领取优惠券吗？")
                                .topK(1).filterExpression("documentId in ['live-allowed']").build());
                        assertThat(hits).hasSize(1);
                        assertThat(hits.get(0).getText()).contains("优惠券");
                    } finally {
                        client.indices().delete(d -> d.index(index));
                    }
                });
    }

    private void awaitCount(ElasticsearchClient client, String index, long expected) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (client.count(c -> c.index(index)).count() == expected) return;
            Thread.sleep(100);
        }
        assertThat(client.count(c -> c.index(index)).count()).isEqualTo(expected);
    }
}
