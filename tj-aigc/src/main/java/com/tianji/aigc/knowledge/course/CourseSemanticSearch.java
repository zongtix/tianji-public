package com.tianji.aigc.knowledge.course;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.Assert;

import java.util.List;

/** 课程语义索引只提供候选 ID，业务事实由课程服务重新查询。 */
public class CourseSemanticSearch {
    private final VectorStore vectors;
    private final int topK;
    private final double threshold;

    public CourseSemanticSearch(VectorStore vectors) {
        this(vectors, 5, 0.6);
    }

    public CourseSemanticSearch(VectorStore vectors, int topK, double threshold) {
        Assert.isTrue(topK > 0, "课程检索 top-k 必须为正数");
        Assert.isTrue(Double.isFinite(threshold) && threshold >= 0 && threshold <= 1,
                "课程检索阈值必须在 0 到 1 之间");
        this.vectors = vectors;
        this.topK = topK;
        this.threshold = threshold;
    }

    public List<Long> search(String goal) {
        Assert.hasText(goal, "学习目标不能为空");
        Assert.isTrue(goal.length() <= 1000, "学习目标过长");
        try {
            return vectors.similaritySearch(SearchRequest.builder().query(goal.strip()).topK(topK)
                            .similarityThreshold(threshold).filterExpression("documentType == 'course'").build())
                    .stream().map(document -> {
                        Object value = document.getMetadata().get("courseId");
                        Assert.state(value instanceof String && ((String) value).matches("[1-9][0-9]{0,18}"),
                                "课程索引缺少合法课程 ID");
                        return Long.parseLong((String) value);
                    }).distinct().limit(topK).toList();
        } catch (RuntimeException error) {
            throw new RetrievalException(error);
        }
    }

    public static boolean isRetrievalFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetrievalException) return true;
        }
        return false;
    }

    public static class RetrievalException extends RuntimeException {
        public RetrievalException(Throwable cause) { super("课程语义检索失败", cause); }
    }
}
