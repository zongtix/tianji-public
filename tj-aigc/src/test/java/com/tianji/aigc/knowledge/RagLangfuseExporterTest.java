package com.tianji.aigc.knowledge;

import com.sun.net.httpserver.HttpServer;
import com.tianji.aigc.config.RagObservabilityConfig;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class RagLangfuseExporterTest {
    @Test
    void enabledContentCaptureIncludesActualToolResultsWhileDefaultFilterDoesNot() {
        var prompt = new org.springframework.ai.chat.prompt.Prompt(java.util.List.of(
                org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(java.util.List.of(
                        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("call-1", "searchCourses", "[{\"name\":\"HTML课程\"}]"))).build()));
        var context = org.springframework.ai.chat.observation.ChatModelObservationContext.builder().prompt(prompt).provider("test").build();
        context.setName("gen_ai.client.operation");
        new RagObservabilityConfig().ragTraceAttributes().map(context);
        assertThat(context.getHighCardinalityKeyValue("langfuse.observation.input")).isNull();
        new RagObservabilityConfig().ragTraceAttributes(true).map(context);
        assertThat(context.getHighCardinalityKeyValue("langfuse.observation.input").getValue())
                .contains("tool_results=", "searchCourses", "HTML课程");
    }

    @Test
    void absentProviderUsageIsMarkedMissingInsteadOfInventingZeroTokens() {
        var context = org.springframework.ai.chat.observation.ChatModelObservationContext.builder()
                .prompt(new org.springframework.ai.chat.prompt.Prompt("test")).provider("openai").build();
        context.setName("gen_ai.client.operation");
        context.setResponse(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of()));
        context.addHighCardinalityKeyValue(io.micrometer.common.KeyValue.of("gen_ai.usage.input_tokens", "0"));
        new RagObservabilityConfig().ragTraceAttributes().map(context);
        assertThat(context.getHighCardinalityKeyValue("gen_ai.usage.input_tokens")).isNull();
        assertThat(context.getHighCardinalityKeyValue("rag.usage.status").getValue()).isEqualTo("unavailable");
    }

    @Test
    void bootUsesTheCustomExporterEvenWhenDefaultOtlpEndpointIsDisabled() {
        var exported = new java.util.concurrent.CopyOnWriteArrayList<io.opentelemetry.sdk.trace.data.SpanData>();
        var exporter = new io.opentelemetry.sdk.trace.export.SpanExporter() {
            public io.opentelemetry.sdk.common.CompletableResultCode export(java.util.Collection<io.opentelemetry.sdk.trace.data.SpanData> values) {
                exported.addAll(values); return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
            }
            public io.opentelemetry.sdk.common.CompletableResultCode flush() { return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess(); }
            public io.opentelemetry.sdk.common.CompletableResultCode shutdown() { return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess(); }
        };
        new ApplicationContextRunner().withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration.class))
                .withUserConfiguration(RagObservabilityConfig.class)
                .withBean(io.opentelemetry.sdk.trace.export.SpanExporter.class, () -> exporter)
                .withPropertyValues("management.otlp.tracing.export.enabled=false", "management.tracing.sampling.probability=1.0",
                        "management.tracing.opentelemetry.export.max-queue-size=8", "management.tracing.opentelemetry.export.max-batch-size=2")
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(io.micrometer.observation.ObservationRegistry.class);
                    var registry = c.getBean(io.micrometer.observation.ObservationRegistry.class);
                    var observedHealth = new java.util.concurrent.atomic.AtomicInteger();
                    registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
                        public boolean supportsContext(io.micrometer.observation.Observation.Context context) { return true; }
                        public void onStop(io.micrometer.observation.Observation.Context context) {
                            var uri = context.getLowCardinalityKeyValue("uri");
                            if (uri != null && uri.getValue().startsWith("/actuator/health")) observedHealth.incrementAndGet();
                        }
                    });
                    io.micrometer.observation.Observation.start("rag.boot-wiring", registry).stop();
                    for (String uri : java.util.List.of("/actuator/health", "/actuator/health/liveness", "/chat", "/actuator/healthcare")) {
                        io.micrometer.observation.Observation.createNotStarted("http.server.requests", registry)
                                .contextualName(uri).lowCardinalityKeyValue("uri", uri).start().stop();
                    }
                    c.getBean(SdkTracerProvider.class).forceFlush().join(3, TimeUnit.SECONDS);
                    assertThat(exported).extracting(io.opentelemetry.sdk.trace.data.SpanData::getName)
                            .containsExactlyInAnyOrder("rag.boot-wiring", "/chat", "/actuator/healthcare");
                    assertThat(observedHealth).hasValue(3);
                });
    }

    @Test
    void disabledLangfuseNeverRequiresCredentialsOrCreatesExporter() {
        new ApplicationContextRunner().withUserConfiguration(RagObservabilityConfig.class)
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean("langfuseSpanExporter"));
    }

    @Test
    void otlpHttpUsesBasicAuthAndBoundedAsynchronousExportWithoutRawContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new CountDownLatch(1); var body = new AtomicReference<byte[]>(); var auth = new AtomicReference<String>();
        server.createContext("/api/public/otel/v1/traces", exchange -> {
            try {
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, -1);
                received.countDown();
            } finally { exchange.close(); }
        });
        server.start();
        try (var exporter = new RagObservabilityConfig().langfuseSpanExporter("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/public/otel/v1/traces", "pk-test", "sk-test");
             var provider = SdkTracerProvider.builder().addSpanProcessor(BatchSpanProcessor.builder(exporter)
                     .setMaxQueueSize(8).setMaxExportBatchSize(2).setScheduleDelay(Duration.ofMillis(30)).build()).build()) {
            provider.get("rag-test").spanBuilder("rag.request").setAttribute("langfuse.session.id", "isolated-eval-session").startSpan().end();
            assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(auth.get()).isEqualTo("Basic " + Base64.getEncoder().encodeToString("pk-test:sk-test".getBytes(StandardCharsets.UTF_8)));
            assertThat(new String(body.get(), StandardCharsets.ISO_8859_1)).contains("rag.request").doesNotContain("pk-test", "sk-test");
        } finally { server.stop(0); }
    }
}
