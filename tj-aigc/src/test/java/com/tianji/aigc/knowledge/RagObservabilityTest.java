package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.*;
import io.micrometer.observation.*;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.common.CompletableResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import reactor.core.publisher.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagObservabilityTest {
    @Test
    void modelCaptureCanBeEnabledWithoutFrameworkContentLogging() {
        var config = new RagObservabilityConfig();
        var context = ChatModelObservationContext.builder().prompt(new Prompt("SYNTHETIC_PROMPT"))
                .provider("test").build();
        context.setName("gen_ai.client.operation");
        context.setResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("SYNTHETIC_ANSWER")))));
        config.ragTraceAttributes(false, true).map(context);
        assertThat(context.getHighCardinalityKeyValue("langfuse.observation.input").getValue()).contains("SYNTHETIC_PROMPT");
        assertThat(context.getHighCardinalityKeyValue("langfuse.observation.output").getValue()).contains("SYNTHETIC_ANSWER");
    }

    @Test
    void toolContentCaptureFollowsTheExistingContentSwitch() {
        var definition = org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("queryCourseById").description("detail").inputSchema("{}").build();
        var hidden = org.springframework.ai.tool.observation.ToolCallingObservationContext.builder()
                .toolDefinition(definition).toolCallArguments("{\"courseId\":42}").toolCallResult("{\"id\":42}").build();
        new RagObservabilityConfig().ragTraceAttributes().map(hidden);
        assertThat(hidden.getHighCardinalityKeyValue("langfuse.observation.input")).isNull();
        assertThat(hidden.getHighCardinalityKeyValue("langfuse.observation.output")).isNull();
        new RagObservabilityConfig().ragTraceAttributes(true).map(hidden);
        assertThat(hidden.getHighCardinalityKeyValue("langfuse.observation.input").getValue()).contains("42");
        assertThat(hidden.getHighCardinalityKeyValue("langfuse.observation.output").getValue()).isEqualTo("{\"id\":42}");
    }

    @Test
    void boundedElasticAndSseKeepOneTracePerRequestAndEndSuccessFailureAndCancellation() {
        List<SpanData> spans = new CopyOnWriteArrayList<>();
        SpanExporter exporter = new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> values) { spans.addAll(values); return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        var previous = ObservationThreadLocalAccessor.getInstance().getObservationRegistry();
        Hooks.enableAutomaticContextPropagation();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var registry = ObservationRegistry.create();
            var tracer = new OtelTracer(provider.get("rag-test"), new OtelCurrentTraceContext(), event -> {});
            registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer))
                    .observationFilter(new RagObservabilityConfig().ragTraceAttributes());
            ObservationThreadLocalAccessor.getInstance().setObservationRegistry(registry);
            var vectors = mock(VectorStore.class);
            when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("证据：不是每天额外加分。")));
            var settings = new KnowledgeRagProperties();
            var telemetry = new RagTelemetry(registry);
            var selector = new KnowledgeContextSelector(settings.getContext());
            var rewriter = mock(KnowledgeQueryRewriter.class);
            when(rewriter.rewrite(any())).thenReturn(Mono.just(new KnowledgeQueryRewriter.Result(false, "问题")));
            var advisor = new KnowledgeRetrievalAdvisor(vectors, SearchRequest.builder().build(),
                    new PromptTemplate("{query}\n{question_answer_context}"), rewriter,
                    new KnowledgeRetrievalPipeline(vectors, null, null, SearchRequest.builder().build(), settings, telemetry), selector, settings, telemetry);
            var chain = mock(StreamAdvisorChain.class);
            when(chain.nextStream(any())).thenAnswer(i -> {
                var request = i.getArgument(0, ChatClientRequest.class);
                return Flux.just(ChatClientResponse.builder().context(request.context()).chatResponse(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("回答"))))).build());
            });
            var request = new ChatClientRequest(new Prompt("问题"), Map.of(QuestionAnswerAdvisor.FILTER_EXPRESSION,
                    "documentId in ['allowed']", ChatMemory.CONVERSATION_ID, "same-session"));
            advisor.adviseStream(request, chain).blockLast(Duration.ofSeconds(5));
            doReturn(Flux.error(new IllegalStateException("sensitive raw detail"))).when(chain).nextStream(any());
            assertThatThrownBy(() -> advisor.adviseStream(request, chain).blockLast(Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
            var subscribed = new java.util.concurrent.atomic.AtomicBoolean();
            when(rewriter.rewrite(any())).thenReturn(Mono.<KnowledgeQueryRewriter.Result>never().doOnSubscribe(s -> subscribed.set(true)));
            var subscription = advisor.adviseStream(request, chain).subscribe();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilTrue(subscribed);
            subscription.dispose();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(spans.stream().filter(s -> s.getName().equals("rag.request"))).hasSize(3));
            var roots = spans.stream().filter(s -> s.getName().equals("rag.request")).toList();
            assertThat(roots).extracting(SpanData::getTraceId).doesNotHaveDuplicates();
            assertThat(roots).allSatisfy(s -> assertThat(s.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.session.id"))).isEqualTo("same-session"));
            assertThat(roots).extracting(s -> s.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("rag.final.status")))
                    .containsExactlyInAnyOrder("ON_COMPLETE", "ON_ERROR", "CANCEL");
            var firstTrace = spans.stream().filter(s -> s.getTraceId().equals(roots.get(0).getTraceId())).toList();
            assertThat(firstTrace).extracting(SpanData::getName).contains("rag.rewrite", "rag.vector", "rag.context");
            assertThat(firstTrace).filteredOn(s -> !s.getName().equals("rag.request"))
                    .allSatisfy(s -> assertThat(s.getParentSpanId()).isEqualTo(roots.get(0).getSpanId()));
            assertThat(spans.toString()).doesNotContain("sensitive raw detail", "证据：", "api-key");
        } finally {
            ObservationThreadLocalAccessor.getInstance().setObservationRegistry(previous);
            Hooks.disableAutomaticContextPropagation();
        }
    }
}
