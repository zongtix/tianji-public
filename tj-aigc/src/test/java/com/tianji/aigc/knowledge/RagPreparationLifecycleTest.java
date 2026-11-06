package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.KnowledgeRagProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagPreparationLifecycleTest {
    @Test
    void preparationTimeoutCancelsEvenAnInterruptIgnoringClientAndPreventsAllDownstreamWork() throws Exception {
        var vectors = mock(VectorStore.class);
        var keywords = mock(KnowledgeKeywordSearch.class);
        var reranker = mock(KnowledgeReranker.class);
        var chain = mock(StreamAdvisorChain.class);
        var rewriter = mock(KnowledgeQueryRewriter.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var returned = new CountDownLatch(1);
        when(rewriter.rewrite(any())).thenReturn(Mono.just(new KnowledgeQueryRewriter.Result(false, "问题")));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(i -> {
            entered.countDown();
            while (true) {
                try { if (release.await(1, TimeUnit.SECONDS)) break; }
                catch (InterruptedException ignored) { /* 模拟不能及时响应取消的现有阻塞客户端。 */ }
            }
            returned.countDown();
            return List.of(new Document("迟到证据"));
        });
        var settings = new KnowledgeRagProperties(); settings.setMode(KnowledgeRagProperties.Mode.C);
        settings.setPreparationTimeout(Duration.ofMillis(250));
        var telemetry = new RagTelemetry(ObservationRegistry.NOOP);
        var advisor = new KnowledgeRetrievalAdvisor(vectors, SearchRequest.builder().build(), new PromptTemplate("{query}\n{question_answer_context}"), rewriter,
                new KnowledgeRetrievalPipeline(vectors, keywords, reranker, SearchRequest.builder().build(), settings, telemetry),
                new KnowledgeContextSelector(settings.getContext()), settings, telemetry);
        var request = new ChatClientRequest(new Prompt("问题"), Map.of(QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId in ['allowed']"));
        try {
            var response = advisor.adviseStream(request, chain).collectList().toFuture();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> response.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(RuntimeException.class);
            release.countDown();
            assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
            // 与 worker 释放后的有效性检查串行：短暂等待所有已排队的 continuation 被消费。
            org.awaitility.Awaitility.await().during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verifyNoInteractions(keywords, reranker, chain));
        } finally { release.countDown(); }
    }
}
