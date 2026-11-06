package com.tianji.aigc.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianji.aigc.config.KnowledgeRagProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.*;

/** DashScope 原生文本排序协议。返回序号映射到原候选，绝不采用供应商返回的正文。 */
public class KnowledgeReranker {
    private final KnowledgeRagProperties.Rerank settings;
    private final RestClient client;
    private final KnowledgeContextSelector tokens;

    public KnowledgeReranker(KnowledgeRagProperties.Rerank settings, KnowledgeContextSelector tokens) {
        this.settings = settings;
        this.tokens = tokens;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.getTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(settings.getTimeout());
        client = RestClient.builder().requestFactory(factory).build();
    }

    public List<RagCandidate> rerank(String query, List<RagCandidate> candidates) {
        if (!settings.isEnabled() || settings.getEndpoint().isBlank() || settings.getApiKey().isBlank()
                || settings.getModel().isBlank()) throw new IllegalStateException("专用 rerank 未配置");
        URI endpoint = URI.create(settings.getEndpoint());
        if (!"https".equals(endpoint.getScheme()) && !("http".equals(endpoint.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost()))) {
            throw new IllegalStateException("rerank 必须使用 HTTPS 或本机测试地址");
        }
        List<RagCandidate> input = candidates.stream().limit(settings.getInputLimit()).toList();
        if (input.isEmpty()) return List.of();
        int queryTokens = tokens.estimate(query);
        int total = 0;
        if (queryTokens > settings.getMaxItemTokens()) throw new IllegalArgumentException("rerank 问题超限");
        for (var candidate : input) {
            int count = tokens.estimate(candidate.document().getText());
            if (count > settings.getMaxItemTokens()) throw new IllegalArgumentException("rerank 片段超限");
            total += count + queryTokens;
        }
        if (total > settings.getMaxRequestTokens()) throw new IllegalArgumentException("rerank 输入超限");
        int expected = Math.min(settings.getOutputLimit(), input.size());
        JsonNode response = client.post().uri(endpoint)
                .header("Authorization", "Bearer " + settings.getApiKey())
                .body(Map.of("model", settings.getModel(), "input", Map.of("query", query,
                                "documents", input.stream().map(c -> c.document().getText()).toList()),
                        "parameters", Map.of("top_n", expected, "return_documents", false)))
                .retrieve().body(JsonNode.class);
        return validate(response, input, expected);
    }

    static List<RagCandidate> validate(JsonNode response, List<RagCandidate> input, int expected) {
        if (response == null || response.hasNonNull("code")) throw new IllegalStateException("rerank 返回错误");
        JsonNode results = response.path("output").path("results");
        if (!results.isArray() || results.size() != expected) throw new IllegalStateException("rerank 结果缺失");
        Set<Integer> seen = new HashSet<>();
        List<RagCandidate> output = new ArrayList<>();
        for (var result : results) {
            JsonNode index = result.path("index");
            JsonNode score = result.path("relevance_score");
            if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                    || index.intValue() >= input.size() || !seen.add(index.intValue()) || !score.isNumber()
                    || !Double.isFinite(score.doubleValue()) || score.doubleValue() < 0 || score.doubleValue() > 1) {
                throw new IllegalStateException("rerank 返回非法候选或分数");
            }
            output.add(input.get(index.intValue()).reranked(score.doubleValue()));
        }
        // 不依赖远端是否按分数排序；同分延续融合排序。
        return output.stream().sorted(Comparator.comparingDouble((RagCandidate c) -> c.rerankScore()).reversed()
                .thenComparingInt(c -> java.util.stream.IntStream.range(0, input.size())
                        .filter(i -> input.get(i).identity().equals(c.identity())).findFirst().orElseThrow())).toList();
    }
}
