package com.tianji.aigc.knowledge;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalAdvisorTimeoutTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void synchronousCancellationAtEachStageFailsFastWithoutWaitingForPreparationTimeout(int completedStages) {
        var vectors = mock(VectorStore.class);
        var rewriter = mock(KnowledgeQueryRewriter.class);
        when(rewriter.rewrite(any())).thenReturn(Mono.just(new KnowledgeQueryRewriter.Result(false, "积分")));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("积分规则")));
        var checks = new AtomicInteger();
        BooleanSupplier active = () -> checks.getAndIncrement() < completedStages;
        var request = new ChatClientRequest(new Prompt("积分"), Map.of(
                QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId == 'points'",
                KnowledgeRetrievalAdvisor.GENERATION_ACTIVE, active));
        var advisor = new KnowledgeRetrievalAdvisor(vectors, SearchRequest.builder().topK(3).build(),
                new PromptTemplate("{query}\n{question_answer_context}"), rewriter, Duration.ofSeconds(30));

        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> advisor.before(request, null))
                        .isInstanceOf(CancellationException.class));
        verify(rewriter, times(completedStages == 0 ? 0 : 1)).rewrite(any());
        verify(vectors, times(completedStages == 2 ? 1 : 0)).similaritySearch(any(SearchRequest.class));
    }
}
