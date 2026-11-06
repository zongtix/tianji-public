package com.tianji.aigc.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class KnowledgeKeywordSearchTest {
    @Test
    void esWireQueryAppliesExactlyTheVectorScopeBeforeScoringAndKeepsSourceIdentity() throws Exception {
        var captured = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tj_aigc_test/_search", exchange -> {
            try {
                captured.set(json.readTree(exchange.getRequestBody()));
                byte[] body = """
                        {"took":1,"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
                         "hits":{"total":{"value":1,"relation":"eq"},"max_score":2.5,"hits":[
                         {"_index":"tj_aigc_test","_id":"chunk-a","_score":2.5,
                          "_source":{"id":"chunk-a","content":"完整规则","metadata":{"documentId":"allowed","contentHash":"v1","chunkIndex":1}}}]}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try (var rest = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            var es = new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
            var scope = new FilterExpressionTextParser().parse("documentId in ['allowed']");
            var result = new KnowledgeKeywordSearch(es, "tj_aigc_test").search("积分 OR *", scope, 5);
            assertThat(captured.get().path("query").path("bool").path("filter").get(0).path("query_string").path("query").asText())
                    .isEqualTo(new ElasticsearchExactMetadataFilterConverter().convertExpression(scope))
                    .contains("metadata.documentId.keyword:");
            // 问题是 match 的值，不能变成 query_string 的过滤语法。
            assertThat(captured.get().path("query").path("bool").path("must").get(0).path("match").path("content").path("query").asText()).isEqualTo("积分 OR *");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("chunk-a");
            assertThat(result.get(0).getScore()).isEqualTo(2.5);
            assertThat(result.get(0).getMetadata()).containsEntry("documentId", "allowed");
        } finally { server.stop(0); }
    }
}
