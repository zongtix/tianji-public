import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tianji.aigc.knowledge.KnowledgeDocumentService;
import com.tianji.aigc.knowledge.material.CourseMaterialDocumentService;
import com.tianji.aigc.knowledge.material.CourseMaterialManifest;
import org.springframework.ai.document.Document;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public class RagCorpus {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        String index = args[2];
        if (!index.matches("tj_aigc_eval_[a-z0-9_]+")) throw new IllegalArgumentException("Use an isolated evaluation index");
        Files.createDirectories(output);
        if (Files.exists(output.resolve("dataset.json"))) throw new IllegalStateException("Dataset already frozen");
        if (index.startsWith("tj_aigc_eval_materials_")) {
            prepareMaterials(repo, output, index);
            return;
        }
        preparePointsCoupons(repo, output, index);
    }

    private static void preparePointsCoupons(Path repo, Path output, String index) throws Exception {
        var service = new KnowledgeDocumentService(null, null, "text-embedding-v4");
        List<Document> chunks = new ArrayList<>();
        List<Map<String, Object>> documents = new ArrayList<>();
        for (var source : List.of(
                Map.of("source", "tj-aigc/src/main/resources/knowledge/tianji-points.md", "id", "tianji-points-guide", "title", "天机学堂积分与签到规则"),
                Map.of("source", "tj-aigc/src/main/resources/knowledge/tianji-coupons.md", "id", "tianji-coupon-guide", "title", "天机学堂优惠券规则"))) {
            var parts = service.readMarkdown(new FileSystemResource(repo.resolve(source.get("source"))), source.get("id"), source.get("title"));
            chunks.addAll(parts);
            documents.add(Map.of("source", source.get("source"), "documentId", source.get("id"),
                    "contentHash", parts.get(0).getMetadata().get("contentHash"), "chunks", parts.size()));
        }
        String corpusHash = hash(JSON.writeValueAsBytes(documents));
        ObjectNode dataset = JSON.createObjectNode();
        dataset.put("datasetVersion", "points-coupons-v1-20261030");
        dataset.put("origin", "Repository rules and AI-designed questions; not user logs or an independent holdout");
        dataset.put("reviewStatus", "AI source review; independent human review pending");
        dataset.put("contentHash", corpusHash);
        dataset.put("index", index);
        dataset.put("corpusFile", "corpus.json");
        dataset.set("documents", JSON.valueToTree(documents));
        ArrayNode cases = dataset.putArray("cases");
        for (JsonNode original : JSON.readTree(repo.resolve("docs/rag-evaluation/cases.json").toFile()).path("cases")) {
            ObjectNode sample = original.deepCopy();
            sample.put("group", "points");
            sample.put("split", "regression");
            sample.set("authorizedDocuments", JSON.valueToTree(List.of("tianji-points-guide", "tianji-coupon-guide")));
            for (JsonNode reference : sample.path("referenceEvidence")) {
                if (chunks.stream().noneMatch(chunk -> matches(chunk, reference))) throw new IllegalStateException("Stale points evidence: " + sample.path("id"));
            }
            cases.add(sample);
        }
        Map<String, String> anchors = Map.ofEntries(
                Map.entry("coupon-01", "每满减：discountType 为 1"),
                Map.entry("coupon-02", "折扣券的折扣值必须在 1~99 之间"),
                Map.entry("coupon-03", "无门槛券的 thresholdAmount 必须为 0"),
                Map.entry("coupon-04", "不是“历史上是否领过”"),
                Map.entry("coupon-05", "累计领取数量（含已使用、已失效）"),
                Map.entry("coupon-06", "不是从活动发放结束时间开始计算"),
                Map.entry("coupon-07", "状态仍为未使用但有效期结束时间不晚于当前时间"),
                Map.entry("coupon-08", "一个兑换码只能成功兑换一次"),
                Map.entry("coupon-09", "仍要经过发放中状态、发放窗口、库存、每人限领和有效期检查"));
        for (JsonNode original : JSON.readTree(repo.resolve("docs/rag-evaluation/coupon-cases-draft.json").toFile()).path("cases")) {
            ObjectNode sample = original.deepCopy();
            sample.put("group", "coupons");
            sample.put("split", "source_reviewed");
            sample.set("authorizedDocuments", JSON.valueToTree(List.of("tianji-points-guide", "tianji-coupon-guide")));
            ArrayNode references = sample.putArray("referenceEvidence");
            if ("answer".equals(sample.path("expectedOutcome").asText())) {
                String anchor = anchors.get(sample.path("id").asText());
                var matching = chunks.stream().filter(chunk -> "tianji-coupon-guide".equals(chunk.getMetadata().get("documentId"))
                        && compact(chunk.getText()).contains(compact(anchor))).toList();
                if (matching.size() != 1) throw new IllegalStateException("Evidence anchor must identify exactly one chunk: " + sample.path("id"));
                var metadata = matching.get(0).getMetadata();
                references.addObject().put("documentId", metadata.get("documentId").toString())
                        .put("contentHash", metadata.get("contentHash").toString()).put("chunkIndex", ((Number)metadata.get("chunkIndex")).intValue());
                sample.put("evidenceAnchor", anchor);
            }
            if ("coupon-02".equals(sample.path("id").asText())) sample.put("question", "创建折扣券时，把折扣值配置成100能通过参数校验吗？");
            if ("coupon-09".equals(sample.path("id").asText())) {
                sample.put("question", "通过兑换码兑换优惠券时，还需要满足发放中状态、发放时间、库存和每人限领条件吗？如果发券失败，兑换码会被消耗吗？");
            }
            cases.add(sample);
        }
        writeCorpus(output, corpusHash, chunks, dataset);
        System.out.println("CORPUS_PREPARED cases=" + cases.size() + " chunks=" + chunks.size() + " index=" + index);
    }

    private static void prepareMaterials(Path repo, Path output, String index) throws Exception {
        Path materialsRoot = repo.resolve("tj-aigc/src/main/resources/course-materials");
        Path manifestPath = materialsRoot.resolve("manifest.json");
        Path draftPath = repo.resolve("docs/rag-evaluation/course-materials/dev-cases-draft.json");
        CourseMaterialManifest manifest = JSON.readValue(manifestPath.toFile(), CourseMaterialManifest.class);
        var importer = new CourseMaterialDocumentService(null, null, "text-embedding-v4");
        List<Document> chunks = new ArrayList<>();
        List<Map<String, Object>> documents = new ArrayList<>();
        List<Map<String, String>> materialHashes = new ArrayList<>();
        Map<String, List<String>> materialsByCourse = new LinkedHashMap<>();
        for (CourseMaterialManifest.Material material : manifest.materials()) {
            if (!"PUBLISHED".equals(material.publishStatus()) || !"PUBLIC_DEMO".equals(material.access())) continue;
            Path source = materialsRoot.resolve(material.sourceFile());
            if (!Files.isRegularFile(source)) throw new IllegalStateException("Missing material file: " + material.sourceFile());
            String fileHash = hash(Files.readAllBytes(source));
            materialHashes.add(Map.of(
                    "materialId", material.materialId(),
                    "courseId", material.courseId(),
                    "sourceFile", "tj-aigc/src/main/resources/course-materials/" + material.sourceFile(),
                    "fileSha256", fileHash));
            var parts = importer.readMarkdown(new FileSystemResource(source), material);
            chunks.addAll(parts);
            documents.add(Map.of(
                    "source", "tj-aigc/src/main/resources/course-materials/" + material.sourceFile(),
                    "documentId", material.materialId(),
                    "courseId", material.courseId(),
                    "contentHash", parts.get(0).getMetadata().get("contentHash"),
                    "chunks", parts.size()));
            materialsByCourse.computeIfAbsent(material.courseId(), ignored -> new ArrayList<>()).add(material.materialId());
        }
        String corpusHash = hash(CANONICAL.writeValueAsBytes(documents));
        String materialsTreeHash = hash(CANONICAL.writeValueAsBytes(materialHashes));
        String manifestHash = hash(Files.readAllBytes(manifestPath));

        JsonNode draft = JSON.readTree(draftPath.toFile());
        if (!"frozen".equals(draft.path("status").asText())) throw new IllegalStateException("dev-cases must be frozen");
        String casesSha256 = hash(CANONICAL.writeValueAsBytes(JSON.treeToValue(draft.path("cases"), Object.class)));
        String expectedCasesSha = draft.path("freeze").path("casesSha256").asText();
        if (!casesSha256.equals(expectedCasesSha)) {
            throw new IllegalStateException("casesSha256 mismatch: computed=" + casesSha256 + " expected=" + expectedCasesSha);
        }

        ObjectNode dataset = JSON.createObjectNode();
        dataset.put("datasetVersion", draft.path("datasetVersion").asText("course-materials-dev-frozen-20261031"));
        dataset.put("status", "frozen");
        dataset.put("origin", draft.path("origin").asText());
        dataset.put("reviewStatus", "AI source review; independent human review pending; holdout untouched");
        dataset.put("contentHash", corpusHash);
        dataset.put("index", index);
        dataset.put("corpusFile", "corpus.json");
        dataset.put("casesSha256", casesSha256);
        dataset.put("manifestSha256", manifestHash);
        dataset.put("materialsTreeSha256", materialsTreeHash);
        dataset.put("sourceCases", "docs/rag-evaluation/course-materials/dev-cases-draft.json");
        dataset.put("holdoutPolicy", "holdout-cases-draft.json not loaded for tuning or scoring");
        dataset.set("documents", JSON.valueToTree(documents));
        dataset.set("materialHashes", JSON.valueToTree(materialHashes));
        ArrayNode cases = dataset.putArray("cases");
        for (JsonNode original : draft.path("cases")) {
            ObjectNode sample = original.deepCopy();
            sample.put("group", "materials");
            List<String> authorized = new ArrayList<>();
            for (JsonNode courseId : original.path("authorizedCourseIds")) {
                authorized.addAll(materialsByCourse.getOrDefault(courseId.asText(), List.of()));
            }
            authorized = authorized.stream().distinct().sorted().toList();
            sample.set("authorizedDocuments", JSON.valueToTree(authorized));
            boolean retrievalComparable = !authorized.isEmpty();
            sample.put("retrievalComparable", retrievalComparable);
            ArrayNode references = sample.putArray("referenceEvidence");
            for (JsonNode anchor : original.path("evidenceAnchors")) {
                String materialId = anchor.path("materialId").asText();
                String quote = anchor.path("quote").asText();
                String heading = anchor.path("heading").asText(null);
                var matching = chunks.stream()
                        .filter(chunk -> materialId.equals(chunk.getMetadata().get("documentId")))
                        .filter(chunk -> compact(chunk.getText()).contains(compact(quote)))
                        .toList();
                if (heading != null && !heading.isBlank()) {
                    var byHeading = matching.stream()
                            .filter(chunk -> heading.equals(Objects.toString(chunk.getMetadata().get("headingPath"), "")))
                            .toList();
                    if (!byHeading.isEmpty()) matching = byHeading;
                }
                if (matching.size() != 1) {
                    throw new IllegalStateException("Evidence anchor must identify exactly one chunk: "
                            + original.path("id").asText() + " material=" + materialId
                            + " matches=" + matching.size());
                }
                var metadata = matching.get(0).getMetadata();
                references.addObject()
                        .put("documentId", metadata.get("documentId").toString())
                        .put("contentHash", metadata.get("contentHash").toString())
                        .put("chunkIndex", ((Number) metadata.get("chunkIndex")).intValue())
                        .put("headingPath", Objects.toString(metadata.get("headingPath"), ""))
                        .put("quote", quote);
            }
            cases.add(sample);
        }
        writeCorpus(output, corpusHash, chunks, dataset);
        System.out.println("CORPUS_PREPARED domain=materials cases=" + cases.size()
                + " chunks=" + chunks.size() + " index=" + index
                + " casesSha256=" + casesSha256
                + " materialsTreeSha256=" + materialsTreeHash
                + " manifestSha256=" + manifestHash);
    }

    private static void writeCorpus(Path output, String corpusHash, List<Document> chunks, ObjectNode dataset) throws Exception {
        ObjectNode corpus = JSON.createObjectNode();
        corpus.put("contentHash", corpusHash);
        corpus.set("documents", JSON.valueToTree(chunks.stream().map(chunk -> Map.of(
                "id", chunk.getId(), "text", chunk.getText(), "metadata", chunk.getMetadata())).toList()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("corpus.json").toFile(), corpus);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("dataset.json").toFile(), dataset);
    }

    static boolean matches(Document chunk, JsonNode reference) {
        var metadata = chunk.getMetadata();
        return reference.path("documentId").asText().equals(metadata.get("documentId"))
                && reference.path("contentHash").asText().equals(metadata.get("contentHash"))
                && reference.path("chunkIndex").asInt() == ((Number)metadata.get("chunkIndex")).intValue();
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
