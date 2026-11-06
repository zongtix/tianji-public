package com.tianji.aigc;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Uses Boot's real client wiring and only writes an isolated AIGC test index. */
@EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_ES_PORT", matches = "19201")
class ElasticsearchIntegrationTest {

    @Test
    void authenticatesWritesSearchesVectorsAndKeepsCourseDataIsolated() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class))
                .withPropertyValues("spring.elasticsearch.uris=http://127.0.0.1:19201",
                        "spring.elasticsearch.username=tj_aigc_app",
                        "spring.elasticsearch.password=" + System.getenv("TJ_AIGC_ES_PASSWORD"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ElasticsearchClient.class);
                    ElasticsearchClient client = context.getBean(ElasticsearchClient.class);
                    assertThat(client.info().version().number()).startsWith("8.");
                    String index = "tj_aigc_client_test_" + UUID.randomUUID().toString().replace("-", "");
                    boolean created = false;
                    try {
                        client.indices().create(c -> c.index(index)
                                .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                                .mappings(m -> m.properties("text", p -> p.text(t -> t))
                                        .properties("embedding", p -> p.denseVector(v -> v.dims(3)
                                                .index(true).similarity(DenseVectorSimilarity.Cosine)))));
                        created = true;
                        client.index(i -> i.index(index).id("near").refresh(Refresh.True)
                                .document(Map.of("text", "课程知识片段", "embedding", List.of(1f, 0f, 0f))));
                        client.index(i -> i.index(index).id("far").refresh(Refresh.True)
                                .document(Map.of("text", "其他知识片段", "embedding", List.of(0f, 1f, 0f))));
                        var result = client.search(s -> s.index(index).knn(k -> k.field("embedding")
                                .queryVector(List.of(1f, 0f, 0f)).k(1).numCandidates(2)), Map.class);
                        assertThat(result.hits().hits()).hasSize(1);
                        assertThat(result.hits().hits().get(0).id()).isEqualTo("near");
                        ElasticsearchException forbidden = assertThrows(ElasticsearchException.class,
                                () -> client.search(s -> s.index("course"), Map.class));
                        assertThat(forbidden.status()).isEqualTo(403);
                    } finally {
                        if (created) {
                            client.indices().delete(d -> d.index(index));
                        }
                    }
                });
    }
}
