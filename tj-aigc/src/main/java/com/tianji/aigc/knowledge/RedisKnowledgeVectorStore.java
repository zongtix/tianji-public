package com.tianji.aigc.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import redis.clients.jedis.search.RediSearchUtil;

import java.util.List;
import java.util.Set;

/** Spring AI 1.1.2 的 Redis TAG 转换未转义特殊字符；仅适配查询值，保留原始文档元数据。 */
public class RedisKnowledgeVectorStore extends RedisVectorStore {
    private static final Set<String> TAG_FIELDS = Set.of("documentId", "contentHash", "source", "embeddingModel");

    public RedisKnowledgeVectorStore(Builder builder) {
        super(builder);
    }

    @Override
    public List<Document> doSimilaritySearch(SearchRequest request) {
        if (!request.hasFilterExpression()) return super.doSimilaritySearch(request);
        var escaped = (Filter.Expression) escape(request.getFilterExpression());
        return super.doSimilaritySearch(SearchRequest.from(request).filterExpression(escaped).build());
    }

    private static Filter.Operand escape(Filter.Operand operand) {
        if (operand instanceof Filter.Group group) {
            return new Filter.Group((Filter.Expression) escape(group.content()));
        }
        if (operand instanceof Filter.Expression expression) {
            if (expression.left() instanceof Filter.Key key && TAG_FIELDS.contains(key.key())
                    && expression.right() instanceof Filter.Value value) {
                Object escaped = value.value() instanceof List<?> values
                        ? values.stream().map(item -> RediSearchUtil.escape(String.valueOf(item))).toList()
                        : RediSearchUtil.escape(String.valueOf(value.value()));
                return new Filter.Expression(expression.type(), key, new Filter.Value(escaped));
            }
            return new Filter.Expression(expression.type(), escape(expression.left()), escape(expression.right()));
        }
        return operand;
    }
}
