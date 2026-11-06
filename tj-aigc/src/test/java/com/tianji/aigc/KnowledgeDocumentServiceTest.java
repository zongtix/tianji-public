package com.tianji.aigc;

import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import com.tianji.aigc.knowledge.KnowledgeDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeDocumentServiceTest {
    private final VectorStore store = mock(VectorStore.class);
    private final KnowledgeDocumentIndex index = mock(KnowledgeDocumentIndex.class);
    private final KnowledgeDocumentService service = new KnowledgeDocumentService(store, index, "text-embedding-v4");
    private final ByteArrayResource resource = new ByteArrayResource(("# 积分说明\n这是一份测试文档。\n\n"
            + "## 学习积分\n每天最多获得50分学习积分。\n\n## 连续签到\n第7天当天共11分。")
            .getBytes(StandardCharsets.UTF_8)) {
        @Override public String getFilename() { return "points.md"; }
    };

    @Test
    void splitsByChapterAndKeepsStableIdsAndTraceableMetadata() throws Exception {
        var first = service.readMarkdown(resource, "points", "积分规则");
        var second = service.readMarkdown(resource, "points", "积分规则");
        assertThat(first).hasSize(3);
        assertThat(first.stream().map(d -> d.getId()).toList())
                .containsExactlyElementsOf(second.stream().map(d -> d.getId()).toList());
        assertThat(first.get(1).getMetadata()).containsEntry("documentId", "points")
                .containsEntry("source", "points.md").containsEntry("chapter", "学习积分")
                .containsEntry("chunkIndex", 2).containsEntry("embeddingModel", "text-embedding-v4");
        assertThat(first.get(1).getText()).contains("积分规则", "学习积分", "50分");
        verifyNoInteractions(store, index);
    }

    @Test
    void couponDraftUsesProductionSplitterWithoutWritingToVectorStore() throws Exception {
        var source = new ClassPathResource("knowledge/tianji-coupons.md");
        var chunks = service.readMarkdown(source, "tianji-coupon-guide", "天机学堂优惠券规则");
        var repeated = service.readMarkdown(source, "tianji-coupon-guide", "天机学堂优惠券规则");
        assertThat(chunks.stream().map(d -> d.getId()).toList())
                .containsExactlyElementsOf(repeated.stream().map(d -> d.getId()).toList());
        assertThat(chunks.stream().map(d -> d.getMetadata().get("chapter")).toList())
                .contains("优惠券类型与金额单位", "公开优惠券列表", "手动领取条件与限领", "有效期", "兑换码", "我的优惠券列表", "本文边界");
        assertThat(chunks.stream().map(d -> d.getMetadata().get("contentHash")).distinct().count()).isEqualTo(1);
        verifyNoInteractions(store, index);
    }

    @Test
    void skipsIdenticalCompleteImportWithoutCallingEmbeddingAgain() throws Exception {
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(storedFrom(chunks));
        assertThat(service.importMarkdown(resource, "points", "积分规则").imported()).isFalse();
        verifyNoInteractions(store);
    }

    @Test
    void rejectsChangedContentWithoutDeletingOrOverwritingExistingKnowledge() throws Exception {
        when(index.countChunks(eq("points"), notNull())).thenReturn(1L);
        assertThrows(IllegalArgumentException.class, () -> service.importMarkdown(resource, "points", "积分规则"));
        verify(index, never()).listChunks(any());
        verifyNoInteractions(store);
    }

    @Test
    void retriesIncompleteImportUsingAllStableChunkIds() throws Exception {
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(storedFrom(chunks.subList(0, 1)));
        var result = service.importMarkdown(resource, "points", "积分规则");
        assertThat(result.imported()).isTrue();
        assertThat(result.chunks()).isEqualTo(3);
        verify(store).add(argThat(docs -> docs.size() == 3 && docs.get(0).getMetadata().get("documentId").equals("points")));
        verifyNoMoreInteractions(store);
    }

    @Test
    void sameCountTitleChangeDoesNotSkipAndReembeds() throws Exception {
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        List<KnowledgeDocumentIndex.StoredChunk> oldTitle = storedFrom(chunks).stream()
                .map(c -> new KnowledgeDocumentIndex.StoredChunk(
                        c.id(), c.contentHash(), "旧标题", c.chapter(), c.embeddingModel(), c.chunkIndex(), c.content()))
                .toList();
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(oldTitle);

        assertThat(service.importMarkdown(resource, "points", "积分规则").imported()).isTrue();
        verify(store).add(argThat(docs -> docs.size() == chunks.size()
                && "积分规则".equals(docs.get(0).getMetadata().get("title"))));
        verify(store, never()).delete(anyList());
    }

    @Test
    void sameCountEmbeddingModelChangeDoesNotSkip() throws Exception {
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        List<KnowledgeDocumentIndex.StoredChunk> oldModel = storedFrom(chunks).stream()
                .map(c -> new KnowledgeDocumentIndex.StoredChunk(
                        c.id(), c.contentHash(), c.title(), c.chapter(), "text-embedding-v3", c.chunkIndex(), c.content()))
                .toList();
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(oldModel);

        assertThat(service.importMarkdown(resource, "points", "积分规则").imported()).isTrue();
        verify(store).add(anyList());
        verify(store, never()).delete(anyList());
    }

    @Test
    void badStoredRowDoesNotCountAsIdenticalLayout() throws Exception {
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        List<KnowledgeDocumentIndex.StoredChunk> bad = new ArrayList<>(storedFrom(chunks));
        var first = bad.get(0);
        bad.set(0, new KnowledgeDocumentIndex.StoredChunk(
                first.id(), first.contentHash(), null, first.chapter(), first.embeddingModel(), first.chunkIndex(), first.content()));
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(bad);

        assertThat(service.importMarkdown(resource, "points", "积分规则").imported()).isTrue();
        verify(store).add(anyList());
        verify(store, never()).delete(anyList());
    }

    @Test
    void sameMetadataWithDifferentChunkTextDoesNotSkip() throws Exception {
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        var oldChunks = new ArrayList<Document>(chunks);
        Document first = chunks.get(0);
        oldChunks.set(0, new Document(first.getId(), first.getText() + " previous boundary", first.getMetadata()));
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(storedFrom(oldChunks));

        assertThat(service.importMarkdown(resource, "points", "积分规则").imported()).isTrue();
        verify(store).add(anyList());
    }

    private static List<KnowledgeDocumentIndex.StoredChunk> storedFrom(List<Document> chunks) {
        return chunks.stream().map(chunk -> new KnowledgeDocumentIndex.StoredChunk(
                chunk.getId(),
                (String) chunk.getMetadata().get("contentHash"),
                (String) chunk.getMetadata().get("title"),
                (String) chunk.getMetadata().get("chapter"),
                (String) chunk.getMetadata().get("embeddingModel"),
                chunk.getMetadata().get("chunkIndex"), chunk.getText())).toList();
    }
}
