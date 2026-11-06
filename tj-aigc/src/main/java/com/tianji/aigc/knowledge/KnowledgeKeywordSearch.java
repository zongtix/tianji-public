package com.tianji.aigc.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/** 与 Spring AI ES 向量查询复用同一个过滤转换器，在 ES 查询内过滤。 */
public class KnowledgeKeywordSearch {
    private final Supplier<ElasticsearchClient> clients;
    private final String index;
    private final FilterExpressionConverter filters;

    public KnowledgeKeywordSearch(ElasticsearchClient client, String index) {
        this(() -> client, index);
    }

    public KnowledgeKeywordSearch(Supplier<ElasticsearchClient> clients, String index) {
        this(clients, index, new ElasticsearchExactMetadataFilterConverter());
    }

    public KnowledgeKeywordSearch(Supplier<ElasticsearchClient> clients, String index,
                                  FilterExpressionConverter filters) {
        this.clients = clients;
        this.index = index;
        this.filters = filters;
    }

    public List<Document> search(String query, Filter.Expression scope, int limit) throws IOException {
        String filter = filters.convertExpression(scope);
        var response = clients.get().search(s -> s.index(index).size(limit)
                .source(source -> source.filter(f -> f.excludes("embedding")))
                .query(q -> q.bool(b -> b.filter(f -> f.queryString(qs -> qs.query(filter)))
                        .must(m -> m.match(match -> match.field("content").query(query)))))
                .sort(sort -> sort.score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                .sort(sort -> sort.field(f -> f.field("id").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc))),
                Document.class);
        if (response.timedOut() || response.shards().failed().intValue() > 0) {
            throw new IOException("关键词检索返回不完整结果");
        }
        return response.hits().hits().stream().map(hit -> {
            if (hit.source() == null) throw new IllegalStateException("关键词检索缺少原文");
            return hit.source().mutate().score(hit.score()).build();
        }).toList();
    }
}
