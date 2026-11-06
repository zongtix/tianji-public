package com.tianji.aigc.knowledge.material;

import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CourseMaterialDocumentServiceTest {
    private final VectorStore store = mock(VectorStore.class);
    private final KnowledgeDocumentIndex index = mock(KnowledgeDocumentIndex.class);
    private final CourseMaterialDocumentService service =
            new CourseMaterialDocumentService(store, index, "text-embedding-v4");
    private final ByteArrayResource resource = new ByteArrayResource(("""
            # 演示讲义

            仅供 AI 演示。

            ## 缓存击穿
            互斥锁避免击穿。

            ## 适用边界
            不要扩大权限。
            """).getBytes(StandardCharsets.UTF_8)) {
        @Override
        public String getFilename() {
            return "redis-hotspot-faq-01.md";
        }
    };
    private final CourseMaterialManifest.Material material = new CourseMaterialManifest.Material(
            "redis-hotspot-faq-01",
            "910000000000010005",
            "【AI演示】Redis 缓存与热点治理",
            "缓存击穿与互斥锁 FAQ",
            "redis-hotspot-faq-01.md",
            "PUBLIC_DEMO",
            "PUBLISHED",
            "演示原创",
            "2026-10-31");

    @Test
    void splitsByChapterAndKeepsStableIdsAndTraceableMetadata() throws Exception {
        var first = service.readMarkdown(resource, material);
        var second = service.readMarkdown(resource, material);
        assertThat(first).isNotEmpty();
        assertThat(first.stream().map(d -> d.getId()).toList())
                .containsExactlyElementsOf(second.stream().map(d -> d.getId()).toList());
        var meta = first.get(0).getMetadata();
        assertThat(meta)
                .containsEntry("courseId", "910000000000010005")
                .containsEntry("materialId", "redis-hotspot-faq-01")
                .containsEntry("documentId", "redis-hotspot-faq-01")
                .containsEntry("sourceId", "redis-hotspot-faq-01")
                .containsEntry("title", "缓存击穿与互斥锁 FAQ")
                .containsEntry("embeddingModel", "text-embedding-v4")
                .containsKeys("headingPath", "contentHash", "chunkIndex");
        assertThat(first.stream().map(d -> d.getMetadata().get("contentHash")).distinct()).hasSize(1);
        verifyNoInteractions(store, index);
    }

    @Test
    void demoFixtureUsesProductionSplitterWithoutWritingToVectorStore() throws Exception {
        var source = new ClassPathResource("course-materials/redis-hotspot-faq-01.md");
        var chunks = service.readMarkdown(source, material);
        var repeated = service.readMarkdown(source, material);
        assertThat(chunks.stream().map(d -> d.getId()).toList())
                .containsExactlyElementsOf(repeated.stream().map(d -> d.getId()).toList());
        assertThat(chunks.stream().map(d -> d.getMetadata().get("headingPath")).toList())
                .anyMatch(h -> h.toString().contains("缓存击穿"));
        verifyNoInteractions(store, index);
    }

    @Test
    void skipsIdenticalCompleteImportWithoutCallingEmbeddingAgain() throws Exception {
        int expectedChunks = service.readMarkdown(resource, material).size();
        when(index.countChunks(eq("redis-hotspot-faq-01"), notNull())).thenReturn(0L);
        when(index.countChunks(eq("redis-hotspot-faq-01"), isNull())).thenReturn((long) expectedChunks);
        assertThat(service.importMarkdown(resource, material).imported()).isFalse();
        verifyNoInteractions(store);
    }

    @Test
    void rejectsChangedContentWithoutDeletingOrOverwritingExistingKnowledge() throws Exception {
        when(index.countChunks(eq("redis-hotspot-faq-01"), notNull())).thenReturn(1L);
        assertThrows(IllegalArgumentException.class, () -> service.importMarkdown(resource, material));
        verifyNoInteractions(store);
    }

    @Test
    void retriesIncompleteImportUsingAllStableChunkIds() throws Exception {
        when(index.countChunks(eq("redis-hotspot-faq-01"), isNull())).thenReturn(1L);
        when(index.countChunks(eq("redis-hotspot-faq-01"), notNull())).thenReturn(0L);
        var result = service.importMarkdown(resource, material);
        assertThat(result.imported()).isTrue();
        assertThat(result.chunks()).isPositive();
        verify(store).add(argThat(docs -> docs.size() == result.chunks()
                && "redis-hotspot-faq-01".equals(docs.get(0).getMetadata().get("materialId"))));
        verifyNoMoreInteractions(store);
    }
}
