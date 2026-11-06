package com.tianji.aigc.tools;

import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.RagCandidate;
import com.tianji.aigc.knowledge.RagRequestGuard;
import com.tianji.aigc.knowledge.material.CourseMaterialAccess;
import com.tianji.aigc.knowledge.material.CourseMaterialSearch;
import com.tianji.aigc.mcp.McpCallScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseMaterialToolsTest {
    private final CourseMaterialSearch search = mock(CourseMaterialSearch.class);
    private final CourseMaterialAccess access =
            CourseMaterialAccess.fromClasspath("course-materials/manifest.json");
    private CourseMaterialTools tools;

    @BeforeEach
    void setUp() {
        tools = new CourseMaterialTools(search, access, 2, 3);
    }

    @Test
    void deniesWhenUserMissing() {
        var result = tools.searchCourseMaterials(910000000000010005L, "缓存击穿", context(null));
        assertThat(result.status()).isEqualTo("ACCESS_DENIED");
        assertThat(result.evidence()).isEmpty();
        verify(search, never()).search(anyString(), any(), any());
    }

    @Test
    void returnsAccessDeniedForNonPilotCourseWithoutCallingSearch() {
        var result = tools.searchCourseMaterials(999L, "缓存击穿", context(1L));
        assertThat(result.status()).isEqualTo("ACCESS_DENIED");
        assertThat(result.evidence()).isEmpty();
        verify(search, never()).search(anyString(), any(), any());
    }

    @Test
    void okReturnsAtMostThreeEvidenceWithCitationFields() {
        when(search.search(eq("缓存击穿"), any(Filter.Expression.class), any(RagRequestGuard.class)))
                .thenReturn(new KnowledgeRetrievalPipeline.Result(List.of(
                        candidate("redis-hotspot-faq-01", "缓存击穿", 1, "hash-a", "互斥锁避免击穿"),
                        candidate("redis-hotspot-faq-01", "适用边界", 2, "hash-a", "第二段"),
                        candidate("redis-penetration-faq-02", "空值缓存", 1, "hash-b", "第三段"),
                        candidate("redis-cache-aside-03", "旁路", 1, "hash-c", "第四段")),
                        List.of(), "not_executed"));
        var result = tools.searchCourseMaterials(910000000000010005L, "缓存击穿", context(1L));
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.courseId()).isEqualTo("910000000000010005");
        assertThat(result.evidence()).hasSize(3);
        assertThat(result.evidence().get(0)).satisfies(e -> {
            assertThat(e.materialId()).isEqualTo("redis-hotspot-faq-01");
            assertThat(e.chapterTitle()).isEqualTo("缓存击穿");
            assertThat(e.chunkIndex()).isEqualTo(1);
            assertThat(e.contentHash()).isEqualTo("hash-a");
            assertThat(e.excerpt()).contains("互斥锁");
            assertThat(e.citationId()).isNotBlank();
        });
    }

    @Test
    void temporarilyUnavailableIsNotEmpty() {
        when(search.search(anyString(), any(), any())).thenThrow(new IllegalStateException("两路知识库检索均失败"));
        var result = tools.searchCourseMaterials(910000000000010005L, "缓存击穿", context(1L));
        assertThat(result.status()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.status()).isNotEqualTo("EMPTY");
    }

    @Test
    void emptySearchReturnsEmptyNotDenied() {
        when(search.search(anyString(), any(), any()))
                .thenReturn(new KnowledgeRetrievalPipeline.Result(List.of(), List.of(), "not_executed"));
        var result = tools.searchCourseMaterials(910000000000010005L, "没有的内容", context(1L));
        assertThat(result.status()).isEqualTo("EMPTY");
    }

    private ToolContext context(Long userId) {
        Map<String, Object> values = new HashMap<>();
        values.put(McpCallScope.CONTEXT_KEY, new McpCallScope(() -> true, () -> true));
        values.put(CourseMaterialTools.TURN_CALLS_KEY, new AtomicInteger(0));
        if (userId != null) values.put(CourseMaterialTools.USER_ID_KEY, userId);
        return new ToolContext(values);
    }

    private RagCandidate candidate(String materialId, String heading, int chunk, String hash, String text) {
        var meta = new HashMap<String, Object>();
        meta.put("materialId", materialId);
        meta.put("headingPath", heading);
        meta.put("chunkIndex", chunk);
        meta.put("contentHash", hash);
        meta.put("courseId", "910000000000010005");
        return new RagCandidate(new Document("id-" + materialId + chunk, text, meta),
                materialId + ":" + hash + ":" + chunk, Map.of(), 1.0, null);
    }
}
