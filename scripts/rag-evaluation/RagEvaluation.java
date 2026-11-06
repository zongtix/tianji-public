import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.*;
import com.tianji.aigc.config.*;
import com.tianji.aigc.knowledge.*;
import io.micrometer.observation.*;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.elasticsearch.*;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.*;

import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 固定评测：默认只读已有隔离索引；仅在明确 InitializeCorpus 时向全新 tj_aigc_eval_* 索引写入语料。凭据仅经 stdin。 */
public class RagEvaluation {
    static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        JsonNode input = JSON.readTree(System.in);
        JsonNode dataset = JSON.readTree(Path.of(args[0]).toFile());
        Path output = Path.of(args[1]);
        boolean answers = input.path("answers").asBoolean();
        Map<String,Object> properties = JSON.convertValue(input.path("properties"), Map.class);
        String datasetIndex = dataset.path("index").asText("").trim();
        Object propertyIndex = properties.get("tj.ai.knowledge.index-name");
        if (datasetIndex.isEmpty() || !datasetIndex.matches("tj_aigc_eval_[a-z0-9_]+")
                || propertyIndex == null || !datasetIndex.equals(propertyIndex.toString())) {
            throw new IllegalArgumentException(
                    "Dataset must declare index matching tj_aigc_eval_* and equal tj.ai.knowledge.index-name; refusing Nacos/production fallback");
        }
        var registry = ObservationRegistry.create();
        Map<String,Long> timings = new LinkedHashMap<>();
        Map<String,Object> usage = new LinkedHashMap<>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context c) { return true; }
            public void onStart(Observation.Context c) { c.put("eval.start", System.nanoTime()); }
            public void onStop(Observation.Context c) {
                // request.doFinally 在下游完成后结束，整体耗时用独立时钟，避免与下一例的 root 回调交错。
                if (!"rag.request".equals(c.getName())) synchronized (timings) {
                    timings.merge(c.getName(), (System.nanoTime() - (long)c.get("eval.start")) / 1000000, Long::sum);
                }
                if (c instanceof org.springframework.ai.chat.observation.ChatModelObservationContext model && model.getResponse() != null) {
                    recordUsage(usage, c, "chat", model.getResponse().getMetadata().getModel(), model.getResponse().getMetadata().getUsage());
                }
                if (c instanceof org.springframework.ai.embedding.observation.EmbeddingModelObservationContext model && model.getResponse() != null) {
                    recordUsage(usage, c, "embedding", model.getResponse().getMetadata().getModel(), model.getResponse().getMetadata().getUsage());
                }
            }
        });
        registry.observationConfig().observationFilter(new RagObservabilityConfig().ragTraceAttributes());
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(registry);
        Hooks.enableAutomaticContextPropagation();
        List<Object> rows = new ArrayList<>();
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("datasetVersion", dataset.path("datasetVersion").asText());
        report.put("startedAt", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        report.put("answersEnabled", answers);
        report.put("properties", properties.entrySet().stream().filter(e -> !e.getKey().matches(".*(api-key|password|secret|endpoint|base-url).*"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        report.put("chatModel", input.path("chatModel").asText());
        report.put("generationOptions", Map.of("temperature", 0.0, "maxTokens", 2048,
                "toolChoice", "none", "thinking", "disabled"));
        report.put("experimentId", input.path("experimentId").asText());
        report.put("codeVersion", input.path("codeVersion").asText());
        report.put("configVersion", "rag-v1");
        report.put("dataVersion", dataset.path("contentHash").asText());
        report.put("datasetHash", RagCorpus.hash(Files.readAllBytes(Path.of(args[0]))));
        report.put("runGroup", input.path("runGroup").asText());
        report.put("promptHash", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.path("systemPrompt").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        report.put("usageCollectionVersion", "v3-chat-and-embedding-timing-by-parent-stage");
        report.put("rows", rows);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("rag-evaluation", properties));
            context.registerBean(ObservationRegistry.class, () -> registry);
            context.registerBean(ChatModel.class, () -> {
                var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
                factory.setReadTimeout(Duration.ofSeconds(45));
                var api = OpenAiApi.builder().apiKey(input.path("chatKey").asText()).baseUrl(input.path("chatBaseUrl").asText())
                        .completionsPath("/chat/completions")
                        .restClientBuilder(org.springframework.web.client.RestClient.builder().requestFactory(factory)).build();
                return OpenAiChatModel.builder().openAiApi(api).observationRegistry(registry)
                        .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                        .defaultOptions(OpenAiChatOptions.builder().model(input.path("chatModel").asText()).temperature(0.0)
                                .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                                .maxTokens(2048).toolChoice("none").internalToolExecutionEnabled(false).build()).build();
            });
            // 先注册 ES 客户端；非 InitializeCorpus 时禁止自动建索引。
            context.register(JacksonAutoConfiguration.class, ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class);
            context.refresh();
            var es = context.getBean(ElasticsearchClient.class);
            String index = properties.get("tj.ai.knowledge.index-name").toString();
            if (!input.path("initializeCorpus").asBoolean() && !es.indices().exists(e -> e.index(index)).value())
                throw new IllegalStateException("评测索引不存在，禁止自动创建");
            if (input.path("initializeCorpus").asBoolean() && (!index.startsWith("tj_aigc_eval_")
                    || es.indices().exists(e -> e.index(index)).value()))
                throw new IllegalStateException("Corpus initialization requires a new isolated index");
            var config = new KnowledgeStoreConfig();
            var embedding = config.dashscopeEmbeddingModel(properties.get("tj.ai.knowledge.embedding.api-key").toString(),
                    properties.get("tj.ai.knowledge.embedding.base-url").toString(), properties.get("tj.ai.knowledge.embedding.model").toString(),
                    Integer.parseInt(properties.get("tj.ai.knowledge.embedding.dimensions").toString()), context.getBeanProvider(ObservationRegistry.class));
            var store = config.knowledgeVectorStore(context.getBean(org.elasticsearch.client.RestClient.class), es, embedding, index, 1024,
                    context.getBeanProvider(ObservationRegistry.class));
            if (input.path("initializeCorpus").asBoolean()) {
                JsonNode corpus = JSON.readTree(Path.of(args[0]).resolveSibling(dataset.path("corpusFile").asText()).toFile());
                if (!dataset.path("contentHash").asText().equals(corpus.path("contentHash").asText()))
                    throw new IllegalArgumentException("Corpus hash differs from dataset");
                List<Document> prepared = new ArrayList<>();
                for (JsonNode document : corpus.path("documents")) prepared.add(new Document(document.path("id").asText(),
                        document.path("text").asText(), JSON.convertValue(document.path("metadata"), Map.class)));
                store.add(prepared);
                long indexed = -1;
                for (int attempt = 0; attempt < 20; attempt++) {
                    indexed = es.count(query -> query.index(index)).count();
                    if (indexed == prepared.size()) break;
                    Thread.sleep(250);
                }
                if (indexed != prepared.size()) {
                    throw new IllegalStateException("Corpus refresh incomplete after wait: expected="
                            + prepared.size() + " actual=" + indexed + " index=" + index);
                }
            }
            Map<String,Object> beforeSnapshot = snapshot(es, index);
            report.put("indexSnapshot", beforeSnapshot);
            if (dataset.has("documents")) {
                long expectedChunks = java.util.stream.StreamSupport.stream(dataset.path("documents").spliterator(), false)
                        .mapToLong(document -> document.path("chunks").asLong()).sum();
                if (!Objects.equals(beforeSnapshot.get("count"), expectedChunks)) throw new IllegalStateException("Unexpected corpus document count");
            }
            var settings = new KnowledgeRagProperties();
            settings.setMode(KnowledgeRagProperties.Mode.valueOf(input.path("mode").asText()));
            if (input.has("snapshot")) {
                var snapshot = input.path("snapshot");
                if (!settings.getMode().name().equals(snapshot.path("mode").asText())) throw new IllegalArgumentException("实验模式与冻结快照不一致");
                settings.setConfigVersion(snapshot.path("configVersion").asText());
                settings.setPromptVersion(snapshot.path("promptVersion").asText());
                settings.getRetrieval().setContextTopK(snapshot.path("contextTopK").asInt());
            }
            settings.setExperimentId(input.path("experimentId").asText());
            settings.setCodeVersion(input.path("codeVersion").asText());
            settings.setDataVersion(dataset.path("contentHash").asText());
            settings.setPreparationTimeout(Duration.ofSeconds(25));
            if (input.has("rerank")) settings.setRerank(JSON.treeToValue(input.path("rerank"), KnowledgeRagProperties.Rerank.class));
            report.put("configVersion", settings.getConfigVersion());
            report.put("promptVersion", settings.getPromptVersion());
            report.put("contextTopK", settings.getRetrieval().getContextTopK());
            report.put("systemPromptHash", report.get("promptHash"));
            String retrievalPrompt = KnowledgeAnswerPrompt.create(settings.getPromptVersion()).getTemplate();
            report.put("retrievalPromptHash", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(retrievalPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            report.put("promptHash", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((input.path("systemPrompt").asText() + "\n" + retrievalPrompt).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            var model = context.getBean(ChatModel.class);
            Map<String,List<Document>> sourceRoutes = new LinkedHashMap<>();
            var telemetry = new RagTelemetry(registry) {
                @Override public <T> T stage(String stage, java.util.concurrent.Callable<T> work) {
                    T value = super.stage(stage, work);
                    if ((stage.equals("vector") || stage.equals("keyword")) && value instanceof List<?> found) {
                        synchronized (sourceRoutes) { sourceRoutes.put(stage, List.copyOf((List<Document>) found)); }
                    }
                    return value;
                }
            };
            var selector = new KnowledgeContextSelector(settings.getContext());
            var search = org.springframework.ai.vectorstore.SearchRequest.builder().topK(3).similarityThreshold(0.6).build();
            var pipeline = new KnowledgeRetrievalPipeline(store, new KnowledgeKeywordSearch(es, index),
                    new KnowledgeReranker(settings.getRerank(), selector), search, settings, telemetry);
            var advisor = new KnowledgeRetrievalAdvisor(store, search, KnowledgeAnswerPrompt.create(settings.getPromptVersion()),
                    new KnowledgeQueryRewriter(model), pipeline, selector, settings, telemetry);
            report.put("mode", settings.getMode());
            for (JsonNode sample : dataset.path("cases")) {
                if (input.has("only") && java.util.stream.StreamSupport.stream(input.path("only").spliterator(), false)
                        .noneMatch(value -> value.asText().equals(sample.path("id").asText()))) continue;
                Map<String,Object> row = new LinkedHashMap<>();
                rows.add(row); row.put("id", sample.path("id").asText()); row.put("split", sample.path("split").asText());
                synchronized (timings) { timings.clear(); }
                synchronized (usage) { usage.clear(); }
                synchronized (sourceRoutes) { sourceRoutes.clear(); }
                row.put("group", sample.path("group").asText("points"));
                long started = System.nanoTime(); AtomicLong first = new AtomicLong();
                try {
                    List<Message> messages = new ArrayList<>();
                    messages.add(new SystemMessage(input.path("systemPrompt").asText()));
                    for (JsonNode history : sample.path("history")) messages.add(history.path("role").asText().equals("USER")
                            ? new UserMessage(history.path("text").asText()) : new AssistantMessage(history.path("text").asText()));
                    messages.add(new UserMessage(sample.path("question").asText()));
                    boolean hasAuthorization = sample.path("authorizedDocuments").isArray() && sample.path("authorizedDocuments").size() > 0;
                    row.put("retrievalComparable", sample.path("retrievalComparable").asBoolean(hasAuthorization));
                    // 讲义评测对齐 CourseMaterialAccess：courseId + materialId；积分/优惠券仍用 documentId。
                    String filter;
                    if (!hasAuthorization) {
                        filter = "documentId == '__no_authorization__'";
                    } else if ("materials".equals(sample.path("group").asText())
                            && sample.path("authorizedCourseIds").isArray()
                            && sample.path("authorizedCourseIds").size() == 1) {
                        String courseId = sample.path("authorizedCourseIds").get(0).asText();
                        String materials = java.util.stream.StreamSupport.stream(sample.path("authorizedDocuments").spliterator(), false)
                                .map(v -> "'" + v.asText() + "'").collect(java.util.stream.Collectors.joining(","));
                        filter = "courseId == '" + courseId + "' && materialId in [" + materials + "]";
                    } else {
                        filter = "documentId in [" + java.util.stream.StreamSupport.stream(sample.path("authorizedDocuments").spliterator(), false)
                                .map(v -> "'" + v.asText() + "'").collect(java.util.stream.Collectors.joining(",")) + "]";
                    }
                    row.put("filterExpression", filter);
                    var request = new ChatClientRequest(new Prompt(messages, OpenAiChatOptions.builder().model(input.path("chatModel").asText())
                            .maxTokens(2048).toolChoice("none").internalToolExecutionEnabled(false).build()), Map.of(
                            QuestionAnswerAdvisor.FILTER_EXPRESSION, filter, ChatMemory.CONVERSATION_ID, "eval:" + UUID.randomUUID()));
                    StreamAdvisorChain chain = new StreamAdvisorChain() {
                        public Flux<ChatClientResponse> nextStream(ChatClientRequest prepared) {
                            return (answers ? model.stream(prepared.prompt()) : Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(""))))))
                                    .map(r -> ChatClientResponse.builder().context(prepared.context()).chatResponse(r).build());
                        }
                        public List<StreamAdvisor> getStreamAdvisors() { return List.of(); }
                        public StreamAdvisorChain copy(StreamAdvisor after) { throw new UnsupportedOperationException(); }
                    };
                    var responses = advisor.adviseStream(request, chain).doOnNext(r -> {
                        if (r.chatResponse().getResult() != null && !Objects.toString(r.chatResponse().getResult().getOutput().getText(), "").isEmpty())
                            first.compareAndSet(0, (System.nanoTime() - started) / 1000000);
                    }).collectList().block(Duration.ofSeconds(90));
                    var last = responses.get(responses.size()-1);
                    List<Document> docs = (List<Document>) last.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
                    row.put("selected", docs.stream().map(d -> Map.of("id", d.getId(), "documentId", d.getMetadata().get("documentId"),
                            "hash", d.getMetadata().get("contentHash"), "chunkIndex", d.getMetadata().get("chunkIndex"), "text", d.getText())).toList());
                    if (sample.has("forbiddenMaterialIds") && sample.path("forbiddenMaterialIds").isArray()) {
                        Set<String> forbidden = java.util.stream.StreamSupport.stream(sample.path("forbiddenMaterialIds").spliterator(), false)
                                .map(JsonNode::asText).collect(java.util.stream.Collectors.toSet());
                        boolean leaked = docs.stream().anyMatch(d -> forbidden.contains(Objects.toString(d.getMetadata().get("documentId"), "")));
                        row.put("forbiddenMaterialLeak", leaked);
                    }
                    int expected = sample.path("referenceEvidence").size();
                    long hits = java.util.stream.StreamSupport.stream(sample.path("referenceEvidence").spliterator(), false).filter(ref -> docs.stream().anyMatch(d ->
                            ref.path("documentId").asText().equals(d.getMetadata().get("documentId")) && ref.path("contentHash").asText().equals(d.getMetadata().get("contentHash"))
                                    && ref.path("chunkIndex").asInt() == ((Number)d.getMetadata().get("chunkIndex")).intValue())).count();
                    row.put("evidenceRecall", expected == 0 ? null : (double)hits / expected);
                    row.put("allNecessaryEvidence", expected == 0 ? null : hits == expected);
                    int rank = 0;
                    for (int i=0;i<docs.size();i++) { final Document document = docs.get(i); if (java.util.stream.StreamSupport.stream(sample.path("referenceEvidence").spliterator(), false)
                            .anyMatch(reference -> RagCorpus.matches(document, reference))) { rank=i+1; break; } }
                    row.put("reciprocalRank", expected == 0 ? null : rank == 0 ? 0.0 : 1.0/rank);
                    Object result = last.context().get(KnowledgeRetrievalAdvisor.RETRIEVAL_RESULT);
                    if (result instanceof KnowledgeRetrievalPipeline.Result retrieval) {
                        row.put("failedRoutes", retrieval.failedRoutes()); row.put("rerankStatus", retrieval.rerankStatus());
                        row.put("candidates", retrieval.candidates().stream().map(c -> Map.of("id", c.identity(), "ranks", c.ranks(), "fusionScore", c.fusionScore(),
                                "rerankScore", c.rerankScore() == null ? "unavailable" : c.rerankScore())).toList());
                        boolean rerankOk = settings.getMode() != KnowledgeRagProperties.Mode.C
                                && settings.getMode() != KnowledgeRagProperties.Mode.D
                                || "success".equals(retrieval.rerankStatus())
                                || ("not_executed".equals(retrieval.rerankStatus()) && retrieval.candidates().isEmpty());
                        row.put("normalModeExecution", retrieval.failedRoutes().isEmpty() && rerankOk);
                    }
                    String answer = responses.stream().filter(r -> r.chatResponse().getResult() != null)
                            .map(r -> Objects.toString(r.chatResponse().getResult().getOutput().getText(), "")).collect(java.util.stream.Collectors.joining());
                    row.put("answer", answers ? answer : null); row.put("answerReview", "pending_manual_review");
                    row.put("responseModel", last.chatResponse().getMetadata().getModel());
                    row.put("firstTextMs", first.get() == 0 ? null : first.get()); row.put("status", "success");
                    row.put("estimatedContextTokens", docs.stream().mapToInt(d -> new KnowledgeContextSelector(settings.getContext()).estimate(d.getText())).sum());
                    synchronized (sourceRoutes) {
                        row.put("routeCandidates", sourceRoutes.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, entry -> entry.getValue().stream().map(document -> Map.of("id", document.getId(),
                                        "documentId", document.getMetadata().get("documentId"), "hash", document.getMetadata().get("contentHash"),
                                        "chunkIndex", document.getMetadata().get("chunkIndex"))).toList())));
                        List<Document> union = sourceRoutes.values().stream().flatMap(List::stream).toList();
                        long candidateHits = java.util.stream.StreamSupport.stream(sample.path("referenceEvidence").spliterator(), false)
                                .filter(reference -> union.stream().anyMatch(document -> RagCorpus.matches(document, reference))).count();
                        row.put("configuredCandidateEvidenceRecall", expected == 0 ? null : (double)candidateHits / expected);
                    }
                } catch (Exception e) { row.put("status", "error"); row.put("errorType", e.getClass().getSimpleName()); }
                row.put("totalMs", (System.nanoTime()-started)/1000000);
                synchronized(timings) { row.put("stageMs", new LinkedHashMap<>(timings)); }
                synchronized(usage) { row.put("usage", new LinkedHashMap<>(usage)); }
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
                System.out.println("EVAL " + settings.getMode() + " " + row.get("id") + " " + row.get("status"));
            }
            report.put("finishedAt", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
            report.put("indexUnchanged", beforeSnapshot.equals(snapshot(es, index)));
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
    }

    static Map<String,Object> snapshot(ElasticsearchClient client, String index) throws Exception {
        final int snapshotSize = 1000;
        var response = client.search(search -> search.index(index).size(snapshotSize).query(query -> query.matchAll(all -> all))
                .sort(sort -> sort.field(field -> field.field("id"))), Map.class);
        long count = client.count(query -> query.index(index)).count();
        int returned = response.hits().hits().size();
        if (response.timedOut() || response.shards().failed().intValue() > 0 || returned != count) {
            throw new IllegalStateException("Incomplete index snapshot: index=" + index + " count=" + count
                    + " returnedHits=" + returned + " searchSize=" + snapshotSize
                    + (count > snapshotSize ? " (corpus exceeds snapshot size; paginate or raise size)" : ""));
        }
        var canonical = JSON.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return Map.of("index", index, "count", count,
                "digest", RagCorpus.hash(canonical.writeValueAsBytes(response.hits().hits().stream().map(hit -> hit.source()).toList())));
    }

    @SuppressWarnings("unchecked")
    static void recordUsage(Map<String,Object> usages, Observation.Context context, String kind, String model,
                            org.springframework.ai.chat.metadata.Usage value) {
        String stage = context.getParentObservation() == null ? "unassociated" : context.getParentObservation().getContextView().getName();
        Map<String,Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind); entry.put("model", model);
        entry.put("durationMs", (System.nanoTime() - (long) context.get("eval.start")) / 1000000);
        boolean known = value != null && !(value instanceof org.springframework.ai.chat.metadata.EmptyUsage)
                && (value.getTotalTokens() > 0 || value.getNativeUsage() != null);
        entry.put("status", known ? "provider_reported" : "unavailable");
        entry.put("usage", known ? value : null);
        synchronized (usages) { ((List<Object>)usages.computeIfAbsent(stage, ignored -> new ArrayList<>())).add(entry); }
    }
}
