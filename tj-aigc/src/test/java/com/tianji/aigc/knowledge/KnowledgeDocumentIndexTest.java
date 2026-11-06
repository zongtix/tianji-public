package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.KnowledgeStoreConfig;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentIndexTest {
    @Test
    @SuppressWarnings("unchecked")
    void elasticsearchReturnsActualHitIdAndChunkTextWithoutEmbedding() throws Exception {
        var client = mock(ElasticsearchClient.class);
        Map<String, Object> source = Map.of("id", "unrelated-source-id", "content", "exact chunk text",
                "metadata", Map.of("contentHash", "hash", "title", "title", "chapter", "chapter",
                        "embeddingModel", "model", "chunkIndex", 1));
        var response = SearchResponse.of(r -> r.took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.total(t -> t.value(1).relation(co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq))
                        .hits(hit -> hit.index("index").id("actual-id").source(source))));
        when(client.search(any(Function.class), eq(Map.class))).thenAnswer(invocation -> {
            Function<co.elastic.clients.elasticsearch.core.SearchRequest.Builder, ?> build = invocation.getArgument(0);
            var builder = new co.elastic.clients.elasticsearch.core.SearchRequest.Builder();
            build.apply(builder);
            var request = builder.build();
            assertThat(request.source().filter().includes()).contains("content");
            assertThat(request.source().filter().excludes()).contains("embedding").doesNotContain("content");
            assertThat(request.query().term().field()).isEqualTo("metadata.documentId.keyword");
            return response;
        });
        var index = new KnowledgeStoreConfig().elasticsearchDocumentIndex(mock(org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore.class), client, "index");
        var chunks = index.listChunks("points");
        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.id()).isEqualTo("actual-id");
            assertThat(chunk.content()).isEqualTo("exact chunk text");
            assertThat(chunk.layoutComplete()).isTrue();
        });
    }

    @Test
    void redisReturnsContentAndRemovesOnlyLeadingConfiguredPrefix() {
        var jedis = mock(JedisPooled.class);
        var result = mock(SearchResult.class);
        when(result.getTotalResults()).thenReturn(1L);
        when(result.getDocuments()).thenReturn(List.of(new Document("prefix:prefix:actual-id", Map.of(
                "content", "exact chunk text", "contentHash", "hash", "title", "title", "chapter", "chapter",
                "embeddingModel", "model", "chunkIndex", "1"))));
        when(jedis.ftSearch(eq("index"), any(Query.class))).thenReturn(result);
        var chunks = new RedisKnowledgeDocumentIndex(jedis, "index", "prefix:").listChunks("points");
        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.id()).isEqualTo("prefix:actual-id");
            assertThat(chunk.content()).isEqualTo("exact chunk text");
            assertThat(chunk.chunkIndex()).isEqualTo(1L);
            assertThat(chunk.layoutComplete()).isTrue();
        });
    }

    @Test
    void redisRejectsPartialEnumerationAndOversizedDocuments() {
        var jedis = mock(JedisPooled.class);
        var result = mock(SearchResult.class);
        when(result.getDocuments()).thenReturn(List.of());
        when(result.getTotalResults()).thenReturn(1L, 513L);
        when(jedis.ftSearch(eq("index"), any(Query.class))).thenReturn(result);
        var index = new RedisKnowledgeDocumentIndex(jedis, "index", "prefix:");
        assertThatThrownBy(() -> index.listChunks("points")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.listChunks("points")).isInstanceOf(IllegalArgumentException.class);
    }
}
