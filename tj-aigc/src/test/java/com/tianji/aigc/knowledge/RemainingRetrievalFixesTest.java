package com.tianji.aigc.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.tianji.aigc.config.KnowledgeRagProperties;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.material.CourseMaterialAccess;
import com.tianji.aigc.knowledge.material.CourseMaterialManifest;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Muse v3 剩余检索项（M2/M3/L2/L4/L14/L15）保护性测试。
 * 父线程统一编译执行前，本文件视为尚未运行。
 */
class RemainingRetrievalFixesTest {

    @Test
    void m2SyncCancelCompletesWithCancellationInsteadOfPreparationTimeout() {
        var vectors = mock(VectorStore.class);
        var rewriter = mock(KnowledgeQueryRewriter.class);
        when(rewriter.rewrite(any())).thenReturn(Mono.just(new KnowledgeQueryRewriter.Result(false, "积分")));
        AtomicInteger checks = new AtomicInteger();
        var request = new ChatClientRequest(new Prompt("积分"), Map.of(
                QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId == 'points'",
                KnowledgeRetrievalAdvisor.GENERATION_ACTIVE, (java.util.function.BooleanSupplier) () -> checks.getAndIncrement() < 1));
        var advisor = new KnowledgeRetrievalAdvisor(vectors, SearchRequest.builder().build(),
                new PromptTemplate("{query}\n{question_answer_context}"), rewriter, Duration.ofSeconds(25));

        long started = System.nanoTime();
        assertThatThrownBy(() -> advisor.before(request, null)).isInstanceOf(CancellationException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        verify(vectors, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void m2StreamLateCancelStillSuppressesAnswerModel() {
        var vectors = mock(VectorStore.class);
        var rewriter = mock(KnowledgeQueryRewriter.class);
        var chain = mock(StreamAdvisorChain.class);
        when(rewriter.rewrite(any())).thenReturn(Mono.just(new KnowledgeQueryRewriter.Result(false, "问题")));
        AtomicBoolean active = new AtomicBoolean(true);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            active.set(false);
            return List.of(new Document("迟到证据"));
        });
        var settings = new KnowledgeRagProperties();
        settings.setPreparationTimeout(Duration.ofSeconds(5));
        var telemetry = new RagTelemetry(ObservationRegistry.NOOP);
        var advisor = new KnowledgeRetrievalAdvisor(vectors, SearchRequest.builder().build(),
                new PromptTemplate("{query}\n{question_answer_context}"), rewriter,
                new KnowledgeRetrievalPipeline(vectors, null, null, SearchRequest.builder().build(), settings, telemetry),
                new KnowledgeContextSelector(settings.getContext()), settings, telemetry);
        var request = new ChatClientRequest(new Prompt("问题"), Map.of(
                QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId in ['allowed']",
                KnowledgeRetrievalAdvisor.GENERATION_ACTIVE, (java.util.function.BooleanSupplier) active::get));

        Flux<Object> suppressed = advisor.adviseStream(request, chain).take(Duration.ofMillis(200)).cast(Object.class);
        assertThat(suppressed.collectList().block(Duration.ofSeconds(2))).isEmpty();
        verifyNoInteractions(chain);
    }

    @Test
    void l2SameHashFewerChunksDeletesOnlyStaleStableIdsAfterSuccessfulAdd() throws Exception {
        var store = mock(VectorStore.class);
        var index = mock(KnowledgeDocumentIndex.class);
        var service = new KnowledgeDocumentService(store, index, "text-embedding-v4");
        var resource = new ByteArrayResource(("# 积分说明\n测试。\n\n## 学习积分\n每天最多获得50分。\n\n## 连续签到\n第7天11分。")
                .getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "points.md"; }
        };
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        String staleHigh = KnowledgeDocumentService.stableChunkId("points", hash, chunks.size() + 2L);
        String staleGap = KnowledgeDocumentService.stableChunkId("points", hash, 99L);
        // 旧库少一个有效分片，另有高 index 与稀疏空洞；按 count 推导会漏删 99。
        List<KnowledgeDocumentIndex.StoredChunk> existing = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.size() - 1; i++) {
            existing.add(stored(chunks.get(i)));
        }
        existing.add(new KnowledgeDocumentIndex.StoredChunk(staleHigh, hash, "积分规则", "残留", "text-embedding-v4", chunks.size() + 2, "old content"));
        existing.add(new KnowledgeDocumentIndex.StoredChunk(staleGap, hash, "积分规则", "空洞", "text-embedding-v4", 99, "old content"));
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(existing);

        assertThat(service.importMarkdown(resource, "points", "积分规则").imported()).isTrue();

        verify(store).add(argThat(docs -> docs.size() == chunks.size()));
        verify(store).delete(org.mockito.ArgumentMatchers.<List<String>>argThat(ids ->
                ids.size() == 2 && ids.containsAll(List.of(staleHigh, staleGap))));
        verifyNoMoreInteractions(store);
    }

    @Test
    void l2SparseOldIdsAreDeletedEvenWhenCountMatchesMaxIndexDerivation() throws Exception {
        var store = mock(VectorStore.class);
        var index = mock(KnowledgeDocumentIndex.class);
        var service = new KnowledgeDocumentService(store, index, "text-embedding-v4");
        var resource = new ByteArrayResource(("# 积分说明\n测试。\n\n## 学习积分\n每天最多获得50分。")
                .getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "points.md"; }
        };
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        // count==新分片数，但旧 ID 为 1 与 50（稀疏）；仅按 count 会静默 skip 且漏删 50。
        String sparse = KnowledgeDocumentService.stableChunkId("points", hash, 50L);
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(List.of(
                stored(chunks.get(0)),
                new KnowledgeDocumentIndex.StoredChunk(sparse, hash, "积分规则", "旧尾", "text-embedding-v4", 50, "old content")));

        assertThat(service.importMarkdown(resource, "points", "积分规则").imported()).isTrue();
        verify(store).add(argThat(docs -> docs.size() == chunks.size()));
        verify(store).delete(org.mockito.ArgumentMatchers.<List<String>>argThat(ids -> ids.equals(List.of(sparse))));
        verifyNoMoreInteractions(store);
    }

    @Test
    void l2IdenticalLayoutStillSkipsEmbedding() throws Exception {
        var store = mock(VectorStore.class);
        var index = mock(KnowledgeDocumentIndex.class);
        var service = new KnowledgeDocumentService(store, index, "text-embedding-v4");
        var resource = new ByteArrayResource(("# 积分说明\n测试。\n\n## 学习积分\n每天最多获得50分。")
                .getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "points.md"; }
        };
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(chunks.stream().map(RemainingRetrievalFixesTest::stored).toList());

        assertThat(service.importMarkdown(resource, "points", "积分规则").imported()).isFalse();
        verifyNoInteractions(store);
    }

    @Test
    void l2FailedAddDoesNotDeleteExistingEvidence() throws Exception {
        var store = mock(VectorStore.class);
        var index = mock(KnowledgeDocumentIndex.class);
        var service = new KnowledgeDocumentService(store, index, "text-embedding-v4");
        var resource = new ByteArrayResource(("# 积分说明\n测试。\n\n## 学习积分\n每天最多获得50分。")
                .getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "points.md"; }
        };
        var chunks = service.readMarkdown(resource, "points", "积分规则");
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        String stale = KnowledgeDocumentService.stableChunkId("points", hash, 9L);
        when(index.countChunks(eq("points"), eq(hash))).thenReturn(0L);
        when(index.listChunks("points")).thenReturn(List.of(
                stored(chunks.get(0)),
                new KnowledgeDocumentIndex.StoredChunk(stale, hash, "积分规则", "旧", "text-embedding-v4", 9, "old content")));
        doThrow(new IllegalStateException("embedding down")).when(store).add(anyList());

        assertThatThrownBy(() -> service.importMarkdown(resource, "points", "积分规则"))
                .isInstanceOf(IllegalStateException.class);
        verify(store, never()).delete(anyList());
    }

    private static KnowledgeDocumentIndex.StoredChunk stored(Document chunk) {
        return new KnowledgeDocumentIndex.StoredChunk(
                chunk.getId(),
                (String) chunk.getMetadata().get("contentHash"),
                (String) chunk.getMetadata().get("title"),
                (String) chunk.getMetadata().get("chapter"),
                (String) chunk.getMetadata().get("embeddingModel"),
                chunk.getMetadata().get("chunkIndex"), chunk.getText());
    }

    @Test
    void l4KeywordIoFailureRecordsIoExceptionNotIllegalState() throws Exception {
        var vectors = mock(VectorStore.class);
        var keywords = mock(KnowledgeKeywordSearch.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("a", "证据", Map.of("documentId", "allowed", "contentHash", "v1", "chunkIndex", 1))));
        when(keywords.search(anyString(), any(), anyInt())).thenThrow(new java.io.IOException("es down"));
        var properties = new KnowledgeRagProperties();
        properties.setMode(KnowledgeRagProperties.Mode.B);
        var pipeline = new KnowledgeRetrievalPipeline(vectors, keywords, mock(KnowledgeReranker.class),
                SearchRequest.builder().topK(3).similarityThreshold(0.6).build(), properties,
                new RagTelemetry(ObservationRegistry.NOOP));

        var result = pipeline.retrieve("问题",
                new FilterExpressionTextParser().parse("documentId in ['allowed']"),
                new RagRequestGuard(() -> true, Duration.ofSeconds(5)));

        assertThat(result.failedRoutes()).containsExactly("keyword:IOException");
        assertThat(result.failedRoutes().toString()).doesNotContain("es down");
    }

    @Test
    void l14DualInstanceCreateConflictIsToleratedAndMappingStillVerified() throws Exception {
        String index = "tj_aigc_knowledge_race";
        var client = mock(ElasticsearchClient.class);
        var indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(java.util.function.Function.class))).thenReturn(new BooleanResponse(false));
        when(indices.create(any(java.util.function.Function.class))).thenThrow(
                new ElasticsearchException("indices.create", ErrorResponse.of(r -> r.status(400)
                        .error(e -> e.type("resource_already_exists_exception").reason("index exists")))));
        when(indices.getMapping(any(java.util.function.Function.class))).thenReturn(GetMappingResponse.of(r -> r.result(index,
                IndexMappingRecord.of(im -> im.mappings(TypeMapping.of(tm -> tm.properties("embedding",
                        Property.of(p -> p.denseVector(v -> v.dims(1024).index(true)
                                .similarity(DenseVectorSimilarity.Cosine))))))))));

        ReflectionTestUtils.invokeMethod(KnowledgeStoreConfig.class, "ensureElasticsearchKnowledgeIndex", client, index, 1024);

        verify(indices).create(any(java.util.function.Function.class));
        verify(indices).getMapping(any(java.util.function.Function.class));
    }

    @Test
    void l14NonAlreadyExistsCreateErrorStillFails() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(java.util.function.Function.class))).thenReturn(new BooleanResponse(false));
        when(indices.create(any(java.util.function.Function.class))).thenThrow(
                new ElasticsearchException("indices.create", ErrorResponse.of(r -> r.status(500)
                        .error(e -> e.type("media_type_header_exception").reason("boom")))));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(KnowledgeStoreConfig.class, "ensureElasticsearchKnowledgeIndex", client, "tj_aigc_x", 1024))
                .isInstanceOf(ElasticsearchException.class)
                .satisfies(error -> assertThat((Boolean) ReflectionTestUtils.invokeMethod(KnowledgeStoreConfig.class, "isResourceAlreadyExists", (ElasticsearchException) error)).isFalse());
        verify(indices, never()).getMapping(any(java.util.function.Function.class));
    }

    @Test
    void l15UnsafeMaterialIdRejectedWithoutBuildingFilter() {
        var toxic = new CourseMaterialManifest.Material(
                "bad' || courseId == 'x", "910000000000010005", "课", "t", "f.md",
                "PUBLIC_DEMO", "PUBLISHED", "o", "2026-10-31");
        var access = new CourseMaterialAccess(new CourseMaterialManifest("demo", List.of(toxic)));
        assertThatThrownBy(() -> access.authorize(1L, "910000000000010005"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("讲义 ID");
    }

    @Test
    void l15SafeManifestStillAuthorizesWithoutWeakeningScope() {
        var access = CourseMaterialAccess.fromClasspath("course-materials/manifest.json");
        var decision = access.authorize(1L, "910000000000010005");
        assertThat(decision.status()).isEqualTo(CourseMaterialAccess.Status.OK);
        assertThat(decision.filterExpression()).startsWith("courseId == '910000000000010005'");
        assertThat(decision.filterExpression()).contains("materialId in [");
        assertThat(access.authorize(1L, "999").status()).isEqualTo(CourseMaterialAccess.Status.ACCESS_DENIED);
    }

    @Test
    void m3EmbeddingReadTimeoutIsWithinDefaultPreparationBudgetAndRoutesStaySerial() {
        assertThat((Duration) ReflectionTestUtils.getField(KnowledgeStoreConfig.class, "EMBEDDING_READ_TIMEOUT")).isEqualTo(Duration.ofSeconds(10));
        assertThat((Duration) ReflectionTestUtils.getField(KnowledgeStoreConfig.class, "EMBEDDING_READ_TIMEOUT"))
                .isLessThanOrEqualTo(new KnowledgeRagProperties().getPreparationTimeout());
        // 并行汇合非本轮强制项：串行保留 terminal 优先与失败顺序可复现（见 Pipeline 注释）。
        assertThat(KnowledgeRetrievalPipeline.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("parallel", "supplyAsync", "invokeAll");
    }
}
