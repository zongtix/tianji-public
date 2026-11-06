package com.tianji.aigc.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 显式执行的一次性导入命令，不随 AIGC 服务启动自动运行。 */
public class KnowledgeImportCommand {
    record Probe(String question, String expectedChapter) {}

    record DocumentSpec(String key, String resourcePath, String documentId, String title, List<Probe> probes) {}

    /** 必须与 application.yml 的 tj.ai.knowledge.public-bases 保持一致，防止导入不可达孤儿文档。 */
    static final Map<String, List<String>> PUBLIC_BASES = Map.of(
            "tianji-points", List.of("tianji-points-guide"),
            "tianji-coupons", List.of("tianji-coupon-guide"));

    private static final Map<String, DocumentSpec> DOCUMENTS = Map.of(
            "points", new DocumentSpec(
                    "points",
                    "knowledge/tianji-points.md",
                    "tianji-points-guide",
                    "天机学堂积分与签到规则",
                    List.of(
                            new Probe("一天学习课程最多能获得多少积分？", "课程学习积分与每日上限"),
                            new Probe("连续签到第七天一共能拿多少分？", "每日签到与连续签到奖励"),
                            new Probe("points_message 的 sent 等于1就表示积分到账了吗？", "完成学习后为什么积分可能稍后到账"))),
            "coupons", new DocumentSpec(
                    "coupons",
                    "knowledge/tianji-coupons.md",
                    "tianji-coupon-guide",
                    "天机学堂优惠券规则",
                    List.of(
                            new Probe("优惠券金额字段是按元还是按分保存？", "优惠券类型与金额单位"),
                            new Probe("领取后优惠券有效期从什么时候开始算？", "有效期"),
                            new Probe("兑换码有哪些字符限制？", "兑换码"))));

    static DocumentSpec requireDocument(String key) {
        String documentKey = StringUtils.hasText(key) ? key.trim() : "points";
        DocumentSpec spec = DOCUMENTS.get(documentKey);
        if (spec == null) {
            throw new IllegalArgumentException("不支持的文档键：" + documentKey + "，可选 points|coupons");
        }
        requirePublishedDocument(spec);
        return spec;
    }

    static void requirePublishedDocument(DocumentSpec document) {
        boolean published = PUBLIC_BASES.values().stream().flatMap(List::stream)
                .anyMatch(id -> id.equals(document.documentId()));
        Assert.state(published, "documentId 未在 public-bases 发布白名单中，拒绝导入孤儿文档：" + document.documentId());
    }

    public static void main(String[] args) throws Exception {
        var properties = new LinkedHashMap<String, Object>();
        String storeType = System.getenv().getOrDefault("TJ_AI_KNOWLEDGE_STORE_TYPE", "elasticsearch");
        Assert.isTrue(List.of("elasticsearch", "redis").contains(storeType), "不支持的向量存储类型");
        properties.put("tj.ai.knowledge.store-type", storeType);
        if (storeType.equals("elasticsearch")) {
            properties.put("spring.elasticsearch.uris", "http://127.0.0.1:19201");
            properties.put("spring.elasticsearch.username", "tj_aigc_app");
            properties.put("spring.elasticsearch.password", requiredEnv("TJ_AIGC_ES_PASSWORD"));
        } else {
            properties.put("tj.ai.knowledge.redis.host", System.getenv().getOrDefault("TJ_AIGC_VECTOR_REDIS_HOST", "127.0.0.1"));
            properties.put("tj.ai.knowledge.redis.port", System.getenv().getOrDefault("TJ_AIGC_VECTOR_REDIS_PORT", "36379"));
            properties.put("tj.ai.knowledge.redis.password", requiredEnv("TJ_AIGC_VECTOR_REDIS_PASSWORD"));
        }
        properties.put("tj.ai.knowledge.enabled", true);
        properties.put("tj.ai.knowledge.embedding.api-key", requiredEnv("DASHSCOPE_API_KEY"));
        properties.put("tj.ai.knowledge.embedding.base-url", requiredEnv("DASHSCOPE_BASE_URL"));
        properties.put("tj.ai.knowledge.embedding.model", "text-embedding-v4");
        properties.put("tj.ai.knowledge.embedding.dimensions", 1024);
        properties.put("tj.ai.knowledge.index-name", "tj_aigc_knowledge_v4_1024");
        DocumentSpec document = requireDocument(args.length > 1 ? args[1] : null);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("knowledge-import", properties));
            context.register(JacksonAutoConfiguration.class, KnowledgeStoreConfig.class, KnowledgeDocumentService.class);
            if (storeType.equals("elasticsearch")) {
                context.register(ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class);
            }
            context.refresh();
            var importer = context.getBean(KnowledgeDocumentService.class);
            var resource = new ClassPathResource(document.resourcePath());
            String documentId = document.documentId();
            var imported = importer.importMarkdown(resource, documentId, document.title());
            var documentIndex = context.getBean(KnowledgeDocumentIndex.class);
            for (int attempt = 0; attempt < 100; attempt++) {
                long count = documentIndex.countChunks(documentId, null);
                if (count == imported.chunks()) break;
                Thread.sleep(100);
            }
            Assert.state(documentIndex.countChunks(documentId, null) == imported.chunks(), "索引未在预期时间内完成片段写入");
            var repeated = importer.importMarkdown(resource, documentId, document.title());
            boolean repeatImportSkipped = !repeated.imported();
            Assert.state(repeatImportSkipped, "重复导入不应重新调用模型或新增片段");
            var store = context.getBean(VectorStore.class);
            List<Map<String, Object>> probes = new ArrayList<>();
            for (Probe probe : document.probes()) {
                var hits = store.similaritySearch(SearchRequest.builder().query(probe.question()).topK(3)
                        .filterExpression("documentId == '" + documentId + "'").build());
                Assert.state(hits.stream().anyMatch(hit -> probe.expectedChapter().equals(hit.getMetadata().get("chapter"))),
                        "检索未命中预期章节：" + probe.expectedChapter());
                probes.add(Map.of("question", probe.question(), "expectedChapter", probe.expectedChapter(),
                        "hits", hits.stream().map(hit -> Map.of("chapter", hit.getMetadata().get("chapter"),
                                "chunkIndex", hit.getMetadata().get("chunkIndex"), "score", hit.getScore())).toList()));
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("storeType", storeType);
            report.put("documentKey", document.key());
            report.put("documentId", documentId);
            report.put("contentHash", imported.contentHash());
            report.put("chunks", imported.chunks());
            report.put("imported", imported.imported());
            report.put("repeatImportSkipped", repeatImportSkipped);
            report.put("publishedInPublicBases", true);
            report.put("publicBasesDocumentIds", PUBLIC_BASES.values().stream().flatMap(List::stream).collect(Collectors.toList()));
            report.put("source", document.resourcePath());
            report.put("retrievalChecks", probes);
            if (args.length > 0) Files.writeString(Path.of(args[0]), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("KNOWLEDGE_IMPORT_OK store=" + storeType + " document=" + document.key()
                    + " chunks=" + imported.chunks() + " probes=" + probes.size()
                    + " repeatImportSkipped=" + repeatImportSkipped);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        Assert.hasText(value, "缺少环境变量：" + name);
        return value;
    }
}
