package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.KnowledgeRagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.*;
import java.util.concurrent.Callable;

/** 每次调用只持有局部候选；生产与评测共用同一实现。 */
public class KnowledgeRetrievalPipeline {
    private final VectorStore vectors;
    private final KnowledgeKeywordSearch keywords;
    private final KnowledgeReranker reranker;
    private final SearchRequest baseline;
    private final KnowledgeRagProperties settings;
    private final RagTelemetry telemetry;

    public KnowledgeRetrievalPipeline(VectorStore vectors, KnowledgeKeywordSearch keywords,
            KnowledgeReranker reranker, SearchRequest baseline, KnowledgeRagProperties settings, RagTelemetry telemetry) {
        this.vectors = vectors;
        this.keywords = keywords;
        this.reranker = reranker;
        this.baseline = baseline;
        this.settings = settings;
        this.telemetry = telemetry;
    }

    public SearchRequest baseline() {
        return baseline;
    }

    public RagTelemetry telemetry() {
        return telemetry;
    }

    public Result retrieve(String query, Filter.Expression scope, RagRequestGuard guard) {
        Objects.requireNonNull(scope, "必须指定可信授权范围");
        var mode = settings.getMode();
        boolean hybrid = mode != KnowledgeRagProperties.Mode.A && mode != KnowledgeRagProperties.Mode.APRIME;
        boolean tuned = mode == KnowledgeRagProperties.Mode.APRIME || hybrid && settings.isInheritAprime();
        SearchRequest request = SearchRequest.from(baseline).query(query).filterExpression(scope)
                .topK(tuned ? settings.getRetrieval().getTunedTopK() : baseline.getTopK())
                .similarityThreshold(tuned ? settings.getRetrieval().getTunedThreshold() : baseline.getSimilarityThreshold()).build();
        guard.check();
        if (!hybrid) {
            List<Document> docs = telemetry.stage("vector", () -> vectors.similaritySearch(request));
            guard.check();
            List<RagCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) candidates.add(new RagCandidate(docs.get(i),
                    RagCandidate.identity(docs.get(i)), Map.of("vector", new RagCandidate.Rank(i + 1, docs.get(i).getScore())), 0, null));
            return new Result(List.copyOf(candidates), List.of(), "not_executed");
        }
        Map<String, List<Document>> routes = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        // 串行是有意设计：保证授权/terminal 异常优先中断、失败路由可复现顺序，以及单路降级口径稳定。
        // 墙钟优化靠单路读超时压进 preparation 预算，而不是改并行汇合架构。
        run("vector", () -> vectors.similaritySearch(request), routes, failures, guard);
        run("keyword", () -> {
            if (keywords == null) throw new IllegalStateException("当前存储不支持 BM25");
            return keywords.search(query, scope, settings.getRetrieval().getKeywordTopK());
        }, routes, failures, guard);
        if (routes.isEmpty()) throw new IllegalStateException("两路知识库检索均失败");
        guard.check();
        List<RagCandidate> fused = telemetry.stage("fusion", () -> RagFusion.fuse(routes,
                settings.getRetrieval().getRankConstant(), settings.getRetrieval().getFusionLimit()));
        telemetry.attribute("degraded.routes", failures);
        telemetry.attribute("fusion.ranks", fused.stream().map(c -> Map.of("id", c.identity(),
                "ranks", c.ranks(), "rrf", c.fusionScore())).toList());
        String rerankStatus = "not_executed";
        if ((mode == KnowledgeRagProperties.Mode.C || mode == KnowledgeRagProperties.Mode.D) && !fused.isEmpty()) {
            guard.check();
            try {
                List<RagCandidate> input = fused;
                fused = telemetry.stage("rerank", () -> reranker.rerank(query, input));
                guard.check();
                rerankStatus = "success";
            } catch (RuntimeException error) {
                if (RagRequestGuard.terminal(error)) throw error;
                guard.check();
                rerankStatus = "fallback:" + RagTelemetry.failureType(error);
            }
        }
        guard.check();
        telemetry.attribute("rerank.status", rerankStatus);
        return new Result(fused, List.copyOf(failures), rerankStatus);
    }

    private void run(String name, Callable<List<Document>> work, Map<String, List<Document>> routes,
                     List<String> failures, RagRequestGuard guard) {
        guard.check();
        try {
            List<Document> found = telemetry.stage(name, work);
            guard.check();
            routes.put(name, List.copyOf(found));
        } catch (RuntimeException error) {
            if (RagRequestGuard.terminal(error)) throw error;
            guard.check();
            failures.add(name + ":" + RagTelemetry.failureType(error));
        }
    }

    public record Result(List<RagCandidate> candidates, List<String> failedRoutes, String rerankStatus) { }
}
