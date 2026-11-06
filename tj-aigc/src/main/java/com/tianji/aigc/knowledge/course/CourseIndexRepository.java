package com.tianji.aigc.knowledge.course;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.Map;

/** 保留旧文档 ID，并用 ES 版本条件阻止并发旧快照覆盖新快照。 */
public class CourseIndexRepository {
    private final ElasticsearchClient client;
    private final String index;

    public CourseIndexRepository(ElasticsearchClient client, String index) {
        Assert.isTrue(index.startsWith("tj_aigc_"), "只允许写入 AIGC 索引");
        this.client = client;
        this.index = index;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Stored find(long courseId) throws IOException {
        var response = client.search(s -> s.index(index).size(2).seqNoPrimaryTerm(true)
                .query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("metadata.courseId.keyword").value(Long.toString(courseId))))
                        .filter(f -> f.terms(t -> t.field("metadata.documentType.keyword")
                                .terms(v -> v.value(java.util.List.of(
                                        co.elastic.clients.elasticsearch._types.FieldValue.of("course"),
                                        co.elastic.clients.elasticsearch._types.FieldValue.of("course_unavailable")))))))), Map.class);
        var hits = response.hits().hits();
        Assert.state(!response.timedOut() && response.shards().failed().intValue() == 0,
                "课程索引查询不完整，禁止覆盖");
        Assert.state(hits.size() <= 1, "课程索引存在重复文档：" + courseId);
        if (hits.isEmpty()) return null;
        var hit = hits.get(0);
        Assert.state(hit.source() != null && hit.seqNo() != null && hit.primaryTerm() != null,
                "课程文档缺少版本信息");
        return new Stored(hit.id(), hit.source(), hit.seqNo(), hit.primaryTerm());
    }

    public void write(Stored previous, Map<String, Object> source) throws IOException {
        var request = new IndexRequest.Builder<Map<String, Object>>()
                .index(index).id(source.get("id").toString()).document(source).refresh(Refresh.WaitFor);
        if (previous == null) request.opType(OpType.Create);
        else request.ifSeqNo(previous.seqNo()).ifPrimaryTerm(previous.primaryTerm());
        client.index(request.build());
    }

    public record Stored(String id, Map<String, Object> source, long seqNo, long primaryTerm) { }
}
