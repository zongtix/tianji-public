import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.*;
import com.tianji.aigc.config.KnowledgeRagProperties;
import com.tianji.aigc.knowledge.*;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.stream.StreamSupport;

/** 固定历史召回结果的离线回放；不创建 ES、embedding、聊天或 rerank 客户端。 */
public class RagCandidateReplay {
    static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path output = Path.of(args[1]);
        JsonNode dataset = JSON.readTree(repo.resolve("docs/rag-evaluation/cases.json").toFile());
        JsonNode captured = JSON.readTree(repo.resolve("docs/rag-evaluation/results/B.json").toFile());
        JsonNode experiments = JSON.readTree(repo.resolve("docs/rag-evaluation/local-fix-experiments.json").toFile());
        var chunks = new KnowledgeDocumentService(null, null, "text-embedding-v4").readMarkdown(
                new ClassPathResource("knowledge/tianji-points.md"), "tianji-points-guide", "天机学堂积分与签到规则");
        Map<String,Document> documents = new HashMap<>();
        for (var chunk : chunks) {
            if (!dataset.path("contentHash").asText().equals(chunk.getMetadata().get("contentHash"))) {
                throw new IllegalStateException("资料版本漂移，不能回放旧候选");
            }
            documents.put(chunk.getId(), chunk);
        }
        Map<String,JsonNode> samples = new HashMap<>();
        for (var sample : dataset.path("cases")) samples.put(sample.path("id").asText(), sample);
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("type", "offline_frozen_candidate_replay");
        report.put("datasetVersion", dataset.path("datasetVersion").asText());
        report.put("dataVersion", dataset.path("contentHash").asText());
        report.put("externalCalls", 0);
        report.put("candidateSnapshot", "docs/rag-evaluation/results/B.json");
        report.put("candidateSnapshotSha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(repo.resolve("docs/rag-evaluation/results/B.json")))));
        report.put("excludedCases", List.of(Map.of("id", "points-32", "reason", "原澄清分支没有召回快照，未重跑改写模型")));
        report.put("limitations", "只回放已保存的候选排名，复用生产融合、选片、提示词与预算代码；不重跑改写、搜索或生成，不证明实时召回、回答质量或外部延迟。");
        List<Map<String,Object>> rows = new ArrayList<>();
        report.put("rows", rows);
        Map<String,Object> summaries = new LinkedHashMap<>();
        report.put("experiments", summaries);
        var configs = experiments.path("experiments").fields();
        while (configs.hasNext()) {
            var entry = configs.next();
            JsonNode snapshot = entry.getValue();
            if (!snapshot.path("mode").asText().equals("B")) throw new IllegalStateException("候选快照仅对应冻结的B模式");
            double recallSum = 0; int annotated = 0, selectedCount = 0, estimated = 0, executed = 0;
            for (var saved : captured.path("rows")) {
                JsonNode sample = samples.get(saved.path("id").asText());
                if (!saved.has("candidates")) continue; // 原澄清分支没有召回快照。
                if (!saved.path("status").asText().equals("success") || saved.path("failedRoutes").size() != 0) {
                    throw new IllegalStateException("候选快照包含失败或降级，不能当作正常对照回放");
                }
                if (sample.path("authorizedDocuments").size() != 1
                        || !sample.path("authorizedDocuments").get(0).asText().equals("tianji-points-guide")) {
                    throw new IllegalStateException("案例授权范围与回放快照不一致");
                }
                var vectorResults = route(saved, "vector", documents);
                var keywordResults = route(saved, "keyword", documents);
                Filter.Expression scope = new FilterExpressionTextParser().parse("documentId in ['tianji-points-guide']");
                VectorStore vectors = (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(), new Class<?>[]{VectorStore.class},
                        (proxy, method, values) -> {
                            if (!method.getName().equals("similaritySearch") || !(values[0] instanceof SearchRequest request)) {
                                throw new UnsupportedOperationException("回放不允许执行其他存储操作");
                            }
                            if (!scope.equals(request.getFilterExpression()) || request.getTopK() != 3 || request.getSimilarityThreshold() != .6) {
                                throw new IllegalStateException("回放意外修改了冻结的检索参数或授权范围");
                            }
                            return vectorResults;
                        });
                var keywords = new KnowledgeKeywordSearch((ElasticsearchClient)null, "unused") {
                    @Override
                    public List<Document> search(String query, Filter.Expression filter, int limit) {
                        if (!scope.equals(filter) || limit != 10) throw new IllegalStateException("关键词参数或授权范围漂移");
                        return keywordResults;
                    }
                };
                var settings = new KnowledgeRagProperties();
                settings.setMode(KnowledgeRagProperties.Mode.B);
                settings.setConfigVersion(snapshot.path("configVersion").asText());
                settings.setPromptVersion(snapshot.path("promptVersion").asText());
                settings.getRetrieval().setContextTopK(snapshot.path("contextTopK").asInt());
                var telemetry = new RagTelemetry(ObservationRegistry.NOOP);
                var selector = new KnowledgeContextSelector(settings.getContext());
                var search = SearchRequest.builder().topK(3).similarityThreshold(.6).build();
                var rewriter = new KnowledgeQueryRewriter(null) {
                    @Override
                    public Mono<Result> rewrite(ChatClientRequest request) {
                        // 已保存候选是本次输入；回放不声称重新验证多轮改写。
                        return Mono.just(new Result(false, sample.path("question").asText()));
                    }
                };
                var advisor = new KnowledgeRetrievalAdvisor(vectors, search,
                        KnowledgeAnswerPrompt.create(settings.getPromptVersion()), rewriter,
                        new KnowledgeRetrievalPipeline(vectors, keywords, null, search, settings, telemetry), selector, settings, telemetry);
                List<Message> messages = new ArrayList<>();
                // 固定的受控系统指令；本回放的完整请求Token不冒充历史Nacos提示词用量。
                messages.add(new SystemMessage("仅依据授权知识资料回答，资料不足时说明。"));
                for (var history : sample.path("history")) messages.add(history.path("role").asText().equals("USER")
                        ? new UserMessage(history.path("text").asText()) : new AssistantMessage(history.path("text").asText()));
                messages.add(new UserMessage(sample.path("question").asText()));
                var request = new ChatClientRequest(new Prompt(messages), Map.of(QuestionAnswerAdvisor.FILTER_EXPRESSION, "documentId in ['tianji-points-guide']"));
                var prepared = advisor.before(request, null);
                @SuppressWarnings("unchecked")
                List<Document> selected = (List<Document>)prepared.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
                List<Integer> indices = selected.stream().map(d -> ((Number)d.getMetadata().get("chunkIndex")).intValue()).toList();
                var fusion = (KnowledgeRetrievalPipeline.Result) prepared.context().get(KnowledgeRetrievalAdvisor.RETRIEVAL_RESULT);
                List<String> fusedIds = fusion.candidates().stream().map(c -> c.document().getId()).toList();
                List<String> savedIds = StreamSupport.stream(saved.path("candidates").spliterator(), false)
                        .map(c -> c.path("id").asText().split(":",2)[0]).toList();
                if (!fusedIds.equals(savedIds)) throw new IllegalStateException("融合排名未能复现原快照");
                if (entry.getKey().equals("control") && !indices.equals(StreamSupport.stream(saved.path("selected").spliterator(), false)
                        .map(c -> c.path("chunkIndex").asInt()).toList())) throw new IllegalStateException("控制组选片未能复现");
                int hits = (int)StreamSupport.stream(sample.path("referenceEvidence").spliterator(), false)
                        .filter(ref -> indices.contains(ref.path("chunkIndex").asInt())).count();
                int count = sample.path("referenceEvidence").size();
                int tokens = selected.stream().mapToInt(d -> selector.estimate(d.getText())).sum();
                if (count > 0) { recallSum += (double)hits/count; annotated++; }
                executed++; selectedCount += selected.size(); estimated += tokens;
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("experiment", entry.getKey()); row.put("id", sample.path("id").asText());
                row.put("promptVersion", settings.getPromptVersion()); row.put("contextTopK", settings.getRetrieval().getContextTopK());
                row.put("selectedChunks", indices); row.put("annotatedEvidenceRecall", count == 0 ? null : (double)hits/count);
                row.put("estimatedContextTokens", tokens); row.put("frozenFusionOrderReproduced", true);
                row.put("answerQuality", "not_evaluated_no_generation");
                rows.add(row);
            }
            summaries.put(entry.getKey(), Map.of("replayedCases", executed, "annotatedCases", annotated,
                    "meanAnnotatedEvidenceRecall", recallSum/annotated, "meanSelectedChunks", (double)selectedCount/executed,
                    "meanEstimatedContextTokens", (double)estimated/executed, "snapshot", snapshot,
                    "retrievalPromptHash", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(KnowledgeAnswerPrompt.create(snapshot.path("promptVersion").asText()).getTemplate()
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        }
        var fourth = rows.stream().filter(r -> r.get("experiment").equals("context4") && r.get("id").equals("points-04")).findFirst().orElseThrow();
        if (!((List<?>)fourth.get("selectedChunks")).contains(2)) throw new IllegalStateException("四片段仍未保留关键章节2");
        output.toAbsolutePath().getParent().toFile().mkdirs();
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println(JSON.writeValueAsString(summaries));
    }

    static List<Document> route(JsonNode saved, String name, Map<String,Document> documents) {
        return StreamSupport.stream(saved.path("candidates").spliterator(), false)
                .filter(c -> c.path("ranks").has(name))
                .sorted(Comparator.comparingInt(c -> c.path("ranks").path(name).path("rank").asInt()))
                .map(c -> {
                    var original = Objects.requireNonNull(documents.get(c.path("id").asText().split(":",2)[0]), "候选来源缺失");
                    JsonNode score = c.path("ranks").path(name).path("score");
                    return original.mutate().score(score.isNumber() ? score.doubleValue() : null).build();
                }).toList();
    }
}
