package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.KnowledgeRagProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalPipelineTest {
    final VectorStore vectors = mock(VectorStore.class);
    final KnowledgeKeywordSearch keywords = mock(KnowledgeKeywordSearch.class);
    final KnowledgeReranker reranker = mock(KnowledgeReranker.class);
    final KnowledgeRagProperties properties = new KnowledgeRagProperties();
    final Filter.Expression scope = new FilterExpressionTextParser().parse("documentId in ['allowed']");

    KnowledgeRetrievalPipeline pipeline(KnowledgeRagProperties.Mode mode) {
        properties.setMode(mode);
        return new KnowledgeRetrievalPipeline(vectors, keywords, reranker,
                SearchRequest.builder().topK(3).similarityThreshold(0.6).build(), properties,
                new RagTelemetry(ObservationRegistry.NOOP));
    }

    RagRequestGuard guard() { return new RagRequestGuard(() -> true, Duration.ofSeconds(5)); }
    Document doc(String id) { return new Document(id, "完整证据，包含否定与例外 " + id, Map.of("documentId", "allowed", "contentHash", "v1", "chunkIndex", 1)); }

    @Test
    void defaultsRetrieveBothRoutesAndFuseTheirEvidenceWithoutCallingRerank() throws Exception {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("shared"), doc("vector-only")));
        when(keywords.search(anyString(), any(), anyInt())).thenReturn(List.of(doc("keyword-only"), doc("shared")));
        var retrieval = new KnowledgeRetrievalPipeline(vectors, keywords, reranker,
                SearchRequest.builder().topK(3).similarityThreshold(0.6).build(), properties,
                new RagTelemetry(ObservationRegistry.NOOP));

        var result = retrieval.retrieve("问题", scope, guard());

        assertThat(result.candidates()).hasSize(3);
        assertThat(result.candidates().get(0).document().getId()).isEqualTo("shared");
        assertThat(result.candidates().get(0).ranks()).containsKeys("vector", "keyword");
        verify(vectors).similaritySearch(argThat((SearchRequest r) -> r.getTopK() == 3
                && r.getSimilarityThreshold() == 0.6 && r.getFilterExpression().equals(scope)));
        verify(keywords).search("问题", scope, 10);
        verifyNoInteractions(reranker);
    }

    @Test
    void rrfAddsBothRoutesAndDeduplicatesWithinEachRouteBeforeRanking() {
        Document a = doc("a"), b = doc("b"), c = doc("c");
        var result = RagFusion.fuse(Map.of("vector", List.of(a, a, b), "keyword", List.of(b, c, a)), 60, 10);
        assertThat(result).hasSize(3);
        var found = result.stream().filter(x -> x.document().getId().equals("a")).findFirst().orElseThrow();
        assertThat(found.fusionScore()).isCloseTo(1.0 / 61 + 1.0 / 63, within(1e-12));
        assertThat(found.ranks()).containsKeys("vector", "keyword");
        assertThat(result.get(0).document().getId()).isEqualTo("b");
        assertThat(result.get(0).ranks().get("vector").rank()).isEqualTo(2);
    }

    @Test
    void tieOrderIsStableAndDifferentVersionsAreNotCollapsed() {
        Document a = doc("a");
        var metadata = new HashMap<>(a.getMetadata());
        metadata.put("contentHash", "v2");
        Document other = new Document(a.getId(), a.getText(), metadata);
        var first = RagFusion.fuse(Map.of("vector", List.of(a), "keyword", List.of(other)), 60, 10);
        var second = RagFusion.fuse(Map.of("keyword", List.of(other), "vector", List.of(a)), 60, 10);
        assertThat(first).hasSize(2).isEqualTo(second);
        metadata = new HashMap<>(a.getMetadata());
        metadata.put("source", "different-source.md");
        var differentSource = new Document(a.getId(), a.getText(), metadata);
        assertThat(RagFusion.fuse(Map.of("vector", List.of(a), "keyword", List.of(differentSource)), 60, 10)).hasSize(2);
    }

    @Test
    void aprimeChangesOnlyVectorParametersWhileHybridInheritsFrozenBase() throws Exception {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("a")));
        when(keywords.search(anyString(), any(), anyInt())).thenReturn(List.of());
        pipeline(KnowledgeRagProperties.Mode.APRIME).retrieve("问题", scope, guard());
        verify(vectors).similaritySearch(argThat((SearchRequest r) -> r.getTopK() == 6 && r.getSimilarityThreshold() == 0.45 && r.getFilterExpression().equals(scope)));
        verifyNoInteractions(keywords, reranker);
        clearInvocations(vectors);
        pipeline(KnowledgeRagProperties.Mode.B).retrieve("问题", scope, guard());
        verify(vectors).similaritySearch(argThat((SearchRequest r) -> r.getTopK() == 3 && r.getSimilarityThreshold() == 0.6 && r.getFilterExpression().equals(scope)));
        verify(keywords).search("问题", scope, 10);
    }

    @Test
    void ordinarySingleRouteFailureUsesSuccessfulResultsAndRecordsIncompleteRetrieval() throws Exception {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("ordinary failure"));
        when(keywords.search(anyString(), any(), anyInt())).thenReturn(List.of(doc("a")));
        var result = pipeline(KnowledgeRagProperties.Mode.B).retrieve("问题", scope, guard());
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.failedRoutes()).hasSize(1);
        when(keywords.search(anyString(), any(), anyInt())).thenReturn(List.of());
        result = pipeline(KnowledgeRagProperties.Mode.B).retrieve("问题", scope, guard());
        assertThat(result.candidates()).isEmpty();
        assertThat(result.failedRoutes()).isNotEmpty();
    }

    @Test
    void bothFailuresAreErrorsButTwoSuccessfulEmptyRoutesAreNotFailures() throws Exception {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("failure"));
        when(keywords.search(anyString(), any(), anyInt())).thenThrow(new java.io.IOException());
        assertThatThrownBy(() -> pipeline(KnowledgeRagProperties.Mode.B).retrieve("问题", scope, guard()))
                .hasMessageContaining("两路");
        doReturn(List.of()).when(vectors).similaritySearch(any(SearchRequest.class));
        doReturn(List.of()).when(keywords).search(anyString(), any(), anyInt());
        var result = pipeline(KnowledgeRagProperties.Mode.B).retrieve("问题", scope, guard());
        assertThat(result.candidates()).isEmpty();
        assertThat(result.failedRoutes()).isEmpty();
    }

    @Test
    void authorizationFailureNeverStartsOtherRouteOrRerank() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException(
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null)));
        assertThatThrownBy(() -> pipeline(KnowledgeRagProperties.Mode.C).retrieve("问题", scope, guard())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(keywords, reranker);
    }

    @Test
    void frameworkEmbeddingPermissionErrorsNeverTriggerKeywordFallback() {
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new org.springframework.ai.retry.NonTransientAiException("401 - private response body"));
        assertThatThrownBy(() -> pipeline(KnowledgeRagProperties.Mode.B).retrieve("问题", scope, guard()))
                .isInstanceOf(org.springframework.ai.retry.NonTransientAiException.class);
        verifyNoInteractions(keywords, reranker);
    }

    @Test
    void cancellationAfterBlockedVectorDiscardsLateResultAndNeverStartsKeyword() {
        AtomicBoolean active = new AtomicBoolean(true);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(i -> { active.set(false); return List.of(doc("late")); });
        assertThatThrownBy(() -> pipeline(KnowledgeRagProperties.Mode.C).retrieve("问题", scope,
                new RagRequestGuard(active::get, Duration.ofSeconds(5)))).isInstanceOf(CancellationException.class);
        verifyNoInteractions(keywords, reranker);
    }

    @Test
    void rerankFailureFallsBackButCancellationDuringRerankDoesNot() throws Exception {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("a")));
        when(keywords.search(anyString(), any(), anyInt())).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList())).thenThrow(new IllegalStateException("invalid response"));
        var result = pipeline(KnowledgeRagProperties.Mode.C).retrieve("问题", scope, guard());
        assertThat(result.rerankStatus()).startsWith("fallback:");
        assertThat(result.candidates()).hasSize(1);
        AtomicBoolean active = new AtomicBoolean(true);
        doAnswer(i -> { active.set(false); throw new IllegalStateException("timeout"); }).when(reranker).rerank(anyString(), anyList());
        assertThatThrownBy(() -> pipeline(KnowledgeRagProperties.Mode.C).retrieve("问题", scope,
                new RagRequestGuard(active::get, Duration.ofSeconds(5)))).isInstanceOf(CancellationException.class);
    }

    @Test
    void successfulRerankReceivesFusedCandidatesAndItsRankingBecomesTheResult() throws Exception {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("a"), doc("b")));
        when(keywords.search(anyString(), any(), anyInt())).thenReturn(List.of(doc("c"), doc("b")));
        when(reranker.rerank(eq("问题"), anyList())).thenAnswer(invocation -> {
            List<RagCandidate> candidates = invocation.getArgument(1);
            assertThat(candidates).extracting(c -> c.document().getId()).containsExactly("b", "a", "c");
            return List.of(candidates.get(2).reranked(0.95), candidates.get(0).reranked(0.75),
                    candidates.get(1).reranked(0.2));
        });

        var result = pipeline(KnowledgeRagProperties.Mode.C).retrieve("问题", scope, guard());

        assertThat(result.rerankStatus()).isEqualTo("success");
        assertThat(result.failedRoutes()).isEmpty();
        assertThat(result.candidates()).extracting(c -> c.document().getId()).containsExactly("c", "b", "a");
        assertThat(result.candidates()).extracting(RagCandidate::rerankScore).containsExactly(0.95, 0.75, 0.2);
        assertThat(result.candidates().get(1).ranks()).containsKeys("vector", "keyword");
    }

    @Test
    void expiredPreparationBudgetNeverStartsAnyCall() {
        assertThatThrownBy(() -> pipeline(KnowledgeRagProperties.Mode.B).retrieve("问题", scope,
                new RagRequestGuard(() -> true, Duration.ofNanos(-1))))
                .isInstanceOf(RagRequestGuard.PreparationTimeoutException.class);
        verifyNoInteractions(vectors, keywords, reranker);
    }
}
