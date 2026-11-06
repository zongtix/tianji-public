package com.tianji.aigc.knowledge;

import org.springframework.ai.document.Document;

import java.util.*;

public final class RagFusion {
    private RagFusion() { }

    public static List<RagCandidate> fuse(Map<String, List<Document>> routes, int constant, int limit) {
        if (constant < 1 || limit < 1) throw new IllegalArgumentException("RRF 参数必须为正数");
        Map<String, Document> documents = new HashMap<>();
        Map<String, Map<String, RagCandidate.Rank>> ranks = new HashMap<>();
        for (var route : routes.entrySet()) {
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (Document document : route.getValue()) {
                String id = RagCandidate.identity(document);
                if (!seen.add(id)) continue;
                documents.putIfAbsent(id, document);
                ranks.computeIfAbsent(id, ignored -> new TreeMap<>())
                        .put(route.getKey(), new RagCandidate.Rank(++rank, document.getScore()));
            }
        }
        return ranks.entrySet().stream().map(e -> new RagCandidate(documents.get(e.getKey()), e.getKey(),
                        Map.copyOf(e.getValue()), e.getValue().values().stream()
                        .mapToDouble(r -> 1.0 / (constant + r.rank())).sum(), null))
                .sorted(Comparator.comparingDouble(RagCandidate::fusionScore).reversed()
                        .thenComparing(RagCandidate::identity)).limit(limit).toList();
    }
}
