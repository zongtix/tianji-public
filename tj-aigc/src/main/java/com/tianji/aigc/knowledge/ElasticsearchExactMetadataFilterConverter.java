package com.tianji.aigc.knowledge;

import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchAiSearchFilterExpressionConverter;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.Set;

/**
 * Spring AI 默认把 metadata 字符串当成 analyzed text 做 query_string 过滤。
 * 同一索引里多个 documentId（如 points / coupons）会因共用分词（tianji、guide）串库。
 * 授权字段改走 .keyword，与 countChunks / 导入校验一致。
 */
public class ElasticsearchExactMetadataFilterConverter extends ElasticsearchAiSearchFilterExpressionConverter {
    private static final Set<String> EXACT_FIELDS = Set.of(
            "documentId", "contentHash", "source", "embeddingModel", "materialId", "courseId", "documentType");

    @Override
    public void doKey(Filter.Key key, StringBuilder context) {
        String identifier = key.key().trim();
        String exact = EXACT_FIELDS.contains(identifier) ? identifier + ".keyword" : identifier;
        super.doKey(new Filter.Key(exact), context);
    }
}
