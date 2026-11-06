package com.tianji.aigc.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianji.aigc.config.KnowledgeRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.*;

class KnowledgeRerankerTest {
    final ObjectMapper json = new ObjectMapper();
    final List<RagCandidate> input = List.of(
            new RagCandidate(new Document("a", "条件：未达到上限才加分", Map.of()), "a", Map.of(), .02, null),
            new RagCandidate(new Document("b", "例外：上限为0表示不限额", Map.of()), "b", Map.of(), .01, null));

    @Test
    void providerConfigurationReusesExistingKeyAndSupportsExplicitRerankOverrides() throws Exception {
        Map<String, Object> environment = new HashMap<>();
        environment.put("DASHSCOPE_API_KEY", "embedding-test-only");
        var sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("test-environment", environment));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        var properties = new PropertySourcesPropertyResolver(sources);
        String prefix = "tj.ai.knowledge.rag.";

        assertThat(properties.getProperty(prefix + "rerank.endpoint"))
                .isEqualTo("https://maas.qianwenaiapi.com/api/v1/services/rerank/text-rerank/text-rerank");
        assertThat(properties.getProperty(prefix + "rerank.model")).isEqualTo("qwen3-rerank");
        assertThat(properties.getProperty(prefix + "rerank.api-key")).isEqualTo("embedding-test-only");
        assertThat(properties.getProperty(prefix + "mode")).isEqualTo("B");
        assertThat(properties.getProperty(prefix + "rerank.enabled", Boolean.class)).isFalse();

        environment.put("TJ_AI_RERANK_API_KEY", "rerank-test-only");
        environment.put("TJ_AI_RERANK_ENABLED", "true");
        environment.put("TJ_AI_KNOWLEDGE_RAG_MODE", "C");
        assertThat(properties.getProperty(prefix + "rerank.api-key")).isEqualTo("rerank-test-only");
        assertThat(properties.getProperty(prefix + "rerank.enabled", Boolean.class)).isTrue();
        assertThat(properties.getProperty(prefix + "mode")).isEqualTo("C");
    }

    @Test
    void localProfileEnablesRerankAfterHybridRetrieval() throws Exception {
        var sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("test-environment",
                Map.of("DASHSCOPE_API_KEY", "embedding-test-only")));
        var loader = new YamlPropertySourceLoader();
        loader.load("application-local", new ClassPathResource("application-local.yml")).forEach(sources::addLast);
        loader.load("application", new ClassPathResource("application.yml")).forEach(sources::addLast);
        var properties = new PropertySourcesPropertyResolver(sources);

        assertThat(properties.getProperty("tj.ai.knowledge.rag.mode")).isEqualTo("C");
        assertThat(properties.getProperty("tj.ai.knowledge.rag.rerank.enabled", Boolean.class)).isTrue();
        assertThat(properties.getProperty("tj.ai.knowledge.rag.rerank.model")).isEqualTo("qwen3-rerank");
        assertThat(properties.getProperty("tj.ai.knowledge.rag.rerank.api-key")).isEqualTo("embedding-test-only");
    }

    @Test
    void httpProtocolUsesDedicatedModelAndMapsIndicesBackToOriginalEvidence() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            try {
                var request = json.readTree(exchange.getRequestBody());
                assertThat(request.path("model").asText()).isEqualTo("dedicated-rerank");
                assertThat(request.path("input").path("documents").size()).isEqualTo(2);
                assertThat(request.path("parameters").path("return_documents").asBoolean()).isFalse();
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-only");
                byte[] body = "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9,\"document\":{\"text\":\"forged\"}},{\"index\":0,\"relevance_score\":0.3}]}}".getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var settings = new KnowledgeRagProperties.Rerank();
            settings.setEnabled(true); settings.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank");
            settings.setApiKey("test-only"); settings.setModel("dedicated-rerank");
            var result = new KnowledgeReranker(settings, new KnowledgeContextSelector(new KnowledgeRagProperties.Context()))
                    .rerank("积分", input);
            assertThat(result).extracting(c -> c.document().getText()).containsExactly(input.get(1).document().getText(), input.get(0).document().getText());
            assertThat(result.get(0).document()).isSameAs(input.get(1).document());
        } finally { server.stop(0); }
    }

    @Test
    void rejectsDuplicateMissingInvalidAndOutOfRangeResults() throws Exception {
        for (String results : List.of("[]", "[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]",
                "[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]",
                "[{\"index\":0.5,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]",
                "[{\"index\":1,\"relevance_score\":1.1},{\"index\":0,\"relevance_score\":0.1}]")) {
            var response = json.readTree("{\"output\":{\"results\":" + results + "}}");
            assertThatThrownBy(() -> KnowledgeReranker.validate(response, input, 2)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void realClientReadTimeoutBoundsBlockingCall() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var settings = new KnowledgeRagProperties.Rerank();
            settings.setEnabled(true); settings.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
            settings.setModel("dedicated"); settings.setApiKey("test-only"); settings.setTimeout(Duration.ofMillis(70));
            assertThatThrownBy(() -> new KnowledgeReranker(settings, new KnowledgeContextSelector(new KnowledgeRagProperties.Context()))
                    .rerank("问题", input)).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        } finally { server.stop(0); }
    }
}
