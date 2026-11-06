package com.tianji.aigc.knowledge;

import org.springframework.ai.document.Document;

import java.util.Map;

/** 身份限于单次索引查询；各路分数不混用，也不表示答案可信度。 */
public record RagCandidate(Document document, String identity, Map<String, Rank> ranks,
                           double fusionScore, Double rerankScore) {
    public record Rank(int rank, Double score) { }

    public static String identity(Document document) {
        var m = document.getMetadata();
        return document.getId() + ":" + m.getOrDefault("documentId", "") + ":"
                + m.getOrDefault("contentHash", "") + ":" + m.getOrDefault("chunkIndex", "")
                + ":" + m.getOrDefault("source", "") + ":" + m.getOrDefault("embeddingModel", "");
    }

    public RagCandidate reranked(double score) {
        return new RagCandidate(document, identity, ranks, fusionScore, score);
    }
}
