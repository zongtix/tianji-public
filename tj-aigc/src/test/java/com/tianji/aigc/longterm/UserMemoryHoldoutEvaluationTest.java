package com.tianji.aigc.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: the checked-in fixture contains synthetic input and prewritten expectations. */
@EnabledIfEnvironmentVariable(named = "TJ_MEMORY_HOLDOUT_EVAL", matches = "true")
class UserMemoryHoldoutEvaluationTest {
    @Test
    void recordSyntheticHoldoutForManualReview() throws Exception {
        var json = new ObjectMapper();
        JsonNode examples;
        try (var input = getClass().getResourceAsStream("/longterm/memory-holdout-v1.json")) {
            assertThat(input).isNotNull();
            examples = json.readTree(input);
        }
        assertThat(examples).hasSize(12);
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertThat(key).isNotBlank();
        var api = OpenAiApi.builder().baseUrl("https://api.deepseek.com")
                .completionsPath("/chat/completions").apiKey(key).build();
        ChatModel delegate = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash")
                        .extraBody(Map.of("thinking", Map.of("type", "disabled"))).build()).build();
        var usage = new AtomicReference<int[]>();
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return delegate.stream(prompt).doOnNext(response -> {
                    var value = response.getMetadata().getUsage();
                    if (value != null && value.getPromptTokens() != null && value.getCompletionTokens() != null
                            && value.getTotalTokens() != null && value.getTotalTokens() > 0)
                        usage.set(new int[]{value.getPromptTokens(), value.getCompletionTokens()});
                });
            }
        };
        var results = new ArrayList<Map<String, Object>>();
        Path path = Path.of("target", "long-memory-evaluation", "holdout-v1.json");
        Files.createDirectories(path.getParent());
        for (JsonNode example : examples) {
            var turns = new ArrayList<UserMemoryStore.Turn>();
            int sequence = 0;
            for (JsonNode turn : example.path("turns")) {
                turns.add(new UserMemoryStore.Turn(++sequence, UUID.randomUUID().toString(), turn.asText(),
                        sequence == 1 && example.has("previousQuestion") ? example.path("previousQuestion").asText() : null));
            }
            var existing = new ArrayList<UserMemoryStore.Memory>();
            if (example.has("existingKey")) existing.add(new UserMemoryStore.Memory(1, example.path("existingKey").asText(),
                    example.path("existingContent").asText(), 1, 1, "old-session", UUID.randomUUID().toString(), LocalDateTime.now()));
            var batch = new UserMemoryStore.Batch(900001, "synthetic-session", 0, "synthetic-token", turns, existing, 1);
            var row = new LinkedHashMap<String, Object>();
            row.put("fixture", example);
            usage.set(null);
            try {
                var candidates = new UserMemoryExtractor(model).extract(batch);
                row.put("candidates", candidates);
                row.put("sourcesValid", candidates.stream().allMatch(candidate -> turns.stream().anyMatch(turn ->
                        turn.requestId().equals(candidate.requestId()) && candidate.evidence() != null
                                && !candidate.evidence().isBlank() && turn.question().contains(candidate.evidence()))));
            } catch (Exception error) {
                row.put("error", error.getClass().getSimpleName());
                results.add(row);
                write(json, path, calls.get(), results);
                throw error;
            }
            int[] tokens = usage.get();
            if (tokens != null) { row.put("inputTokens", tokens[0]); row.put("outputTokens", tokens[1]); }
            results.add(row);
            write(json, path, calls.get(), results);
        }
        assertThat(results).hasSize(examples.size());
    }

    private static void write(ObjectMapper json, Path path, int calls, List<Map<String, Object>> results) throws Exception {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), Map.of("model", "deepseek-flash",
                "thinkingDisabled", true, "syntheticOnly", true, "callsAttempted", calls, "cases", results));
    }
}
