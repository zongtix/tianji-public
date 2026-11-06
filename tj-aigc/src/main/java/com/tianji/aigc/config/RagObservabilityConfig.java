package com.tianji.aigc.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** 复用 Boot 的 Micrometer/OTel bridge 与异步有限队列，不另建第二套 tracer。 */
@Configuration(proxyBeanMethods = false)
public class RagObservabilityConfig {
    /** 仅过滤 trace 导出，保留健康接口及其 HTTP 指标。 */
    @Bean
    public SpanExportingPredicate excludeHealthTraces() {
        return span -> {
            String uri = span.getTags().get("uri");
            if (uri == null) uri = span.getTags().get("http.route");
            return uri == null || !(uri.equals("/actuator/health") || uri.startsWith("/actuator/health/"));
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.knowledge.observation.langfuse", name = "enabled", havingValue = "true")
    public OtlpHttpSpanExporter langfuseSpanExporter(
            @Value("${tj.ai.knowledge.observation.langfuse.endpoint}") String endpoint,
            @Value("${tj.ai.knowledge.observation.langfuse.public-key}") String publicKey,
            @Value("${tj.ai.knowledge.observation.langfuse.secret-key}") String secretKey) {
        Assert.hasText(publicKey, "请配置 Langfuse public key");
        Assert.hasText(secretKey, "请配置 Langfuse secret key");
        var uri = java.net.URI.create(endpoint);
        Assert.isTrue("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()),
                "Langfuse 必须使用 HTTPS 或已授权的本机实例");
        String auth = Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        return OtlpHttpSpanExporter.builder().setEndpoint(endpoint).setTimeout(Duration.ofSeconds(2))
                .addHeader("Authorization", "Basic " + auth).addHeader("x-langfuse-ingestion-version", "4").build();
    }

    @Bean
    public ObservationFilter ragTraceAttributes(
            @Value("${spring.ai.chat.observations.log-prompt:false}") boolean legacyContent,
            @Value("${tj.ai.observation.model-content:false}") boolean modelContent) {
        return ragTraceAttributes(legacyContent || modelContent);
    }

    public ObservationFilter ragTraceAttributes(boolean captureContent) {
        return context -> {
            ObservationView parent = context.getParentObservation();
            while (parent != null) {
                var source = parent.getContextView();
                for (KeyValue value : source.getHighCardinalityKeyValues()) {
                    if (value.getKey().startsWith("rag.") || value.getKey().equals("langfuse.session.id")) {
                        if (context.getHighCardinalityKeyValue(value.getKey()) == null) context.addHighCardinalityKeyValue(value);
                    }
                }
                parent = source.getParentObservation();
            }
            if (captureContent && context instanceof org.springframework.ai.tool.observation.ToolCallingObservationContext tool) {
                context.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.input", truncate(tool.getToolCallArguments())));
                if (tool.getToolCallResult() != null) {
                    context.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.output", truncate(tool.getToolCallResult())));
                }
            }
            if ("gen_ai.client.operation".equals(context.getName())
                    && context instanceof org.springframework.ai.chat.observation.ChatModelObservationContext model) {
                context.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.type", "generation"));
                if (captureContent) captureContent(model, context);
                if (model.getResponse() != null && model.getResponse().getMetadata().getUsage() != null) {
                    var usage = model.getResponse().getMetadata().getUsage();
                    context.removeHighCardinalityKeyValues("gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "gen_ai.usage.total_tokens");
                    if (usage instanceof org.springframework.ai.chat.metadata.EmptyUsage
                            || usage.getTotalTokens() == 0 && usage.getNativeUsage() == null) {
                        context.addHighCardinalityKeyValue(KeyValue.of("rag.usage.status", "unavailable"));
                        return context;
                    }
                    Integer input = usage.getPromptTokens(), output = usage.getCompletionTokens();
                    if (usage.getNativeUsage() instanceof org.springframework.ai.openai.api.OpenAiApi.Usage nativeUsage) {
                        input = nativeUsage.promptTokens(); output = nativeUsage.completionTokens();
                    }
                    // 只映射供应商实际返回的用量，不以估算补齐计费。
                    if (input != null) context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.input_tokens", input.toString()));
                    if (output != null) context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.output_tokens", output.toString()));
                    context.addHighCardinalityKeyValue(KeyValue.of("rag.usage.status", input == null || output == null ? "partial" : "available"));
                } else context.addHighCardinalityKeyValue(KeyValue.of("rag.usage.status", "unavailable"));
            }
            return context;
        };
    }

    /** 无参重载：供评测脚本与单测直接构造，不捕获对话内容。 */
    public ObservationFilter ragTraceAttributes() {
        return ragTraceAttributes(false);
    }

    /**
     * 把本次真实的 prompt / completion 写进 Langfuse 的 input/output。
     * 只靠 spring.ai.chat.observations.log-prompt 是不够的：它只写应用日志（ChatModelPromptContentObservationHandler），
     * 不进 span；Langfuse 只认 langfuse.observation.input / langfuse.observation.output 这两个属性名。
     */
    private static void captureContent(org.springframework.ai.chat.observation.ChatModelObservationContext model,
                                       io.micrometer.observation.Observation.Context context) {
        String prompt = promptText(model.getRequest());
        if (prompt != null) {
            context.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.input", truncate(prompt)));
        }
        if (model.getResponse() != null && model.getResponse().getResult() != null) {
            String completion = model.getResponse().getResult().getOutput().getText();
            if (completion != null) {
                context.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.output", truncate(completion)));
            }
        }
    }

    private static String promptText(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) return null;
        StringBuilder text = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            text.append('[').append(message.getMessageType().getValue()).append(']');
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                text.append(" tool_calls=").append(assistant.getToolCalls().stream()
                        .map(call -> call.name() + call.arguments()).toList());
            }
            if (message instanceof ToolResponseMessage tool) {
                text.append(" tool_results=").append(tool.getResponses().stream()
                        .map(response -> response.name() + response.responseData()).toList());
            }
            if (message.getText() != null) text.append(' ').append(message.getText());
            text.append('\n');
        }
        return text.toString();
    }

    /** 单条 span 属性上限 8k 字符，避免把 trace 撑爆。 */
    private static String truncate(String value) {
        return value.length() <= 8000 ? value : value.substring(0, 8000) + "…[truncated]";
    }
}
