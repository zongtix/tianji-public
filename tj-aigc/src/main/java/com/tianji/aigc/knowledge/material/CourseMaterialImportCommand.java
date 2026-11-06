package com.tianji.aigc.knowledge.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
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

/** 显式讲义导入入口；不随 AIGC 服务启动自动运行。 */
public class CourseMaterialImportCommand {
    public static void main(String[] args) throws Exception {
        Assert.isTrue(args.length == 1, "请指定报告文件路径");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("tj.ai.knowledge.enabled", true);
        properties.put("tj.ai.course-materials.enabled", true);
        properties.put("tj.ai.knowledge.store-type", "elasticsearch");
        properties.put("spring.elasticsearch.uris",
                System.getenv().getOrDefault("SPRING_ELASTICSEARCH_URIS", "http://127.0.0.1:19201"));
        properties.put("spring.elasticsearch.username",
                System.getenv().getOrDefault("SPRING_ELASTICSEARCH_USERNAME", "tj_aigc_app"));
        properties.put("spring.elasticsearch.password", requiredEnv("TJ_AIGC_ES_PASSWORD"));
        properties.put("tj.ai.knowledge.embedding.api-key", requiredEnv("DASHSCOPE_API_KEY"));
        properties.put("tj.ai.knowledge.embedding.base-url", requiredEnv("DASHSCOPE_BASE_URL"));
        properties.put("tj.ai.knowledge.embedding.model", "text-embedding-v4");
        properties.put("tj.ai.knowledge.embedding.dimensions", 1024);
        String index = System.getenv().getOrDefault("TJ_AI_COURSE_MATERIALS_INDEX", CourseMaterialSearch.INDEX);
        Assert.isTrue(CourseMaterialSearch.INDEX.equals(index) || index.startsWith("tj_aigc_eval_materials_"),
                "讲义导入只允许生产讲义索引或 tj_aigc_eval_materials_<batch>");
        properties.put("tj.ai.knowledge.index-name", index);
        properties.put("tj.ai.course-materials.index-name", index);

        CourseMaterialManifest manifest = CourseMaterialManifest.fromClasspath("course-materials/manifest.json");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("course-material-import", properties));
            context.register(JacksonAutoConfiguration.class, KnowledgeStoreConfig.class,
                    ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class);
            context.refresh();
            var documentIndex = context.getBean(KnowledgeDocumentIndex.class);
            var importer = new CourseMaterialDocumentService(
                    context.getBean(VectorStore.class), documentIndex, "text-embedding-v4");
            List<Map<String, Object>> results = new ArrayList<>();
            for (CourseMaterialManifest.Material material : manifest.materials()) {
                if (!"PUBLISHED".equals(material.publishStatus()) || !"PUBLIC_DEMO".equals(material.access())) {
                    continue;
                }
                var resource = new ClassPathResource("course-materials/" + material.sourceFile());
                Assert.state(resource.exists(), "缺少讲义源文件: " + material.sourceFile());
                var imported = importer.importMarkdown(resource, material);
                for (int attempt = 0; attempt < 300; attempt++) {
                    long count = documentIndex.countChunks(material.materialId(), null);
                    if (count == imported.chunks()) break;
                    Thread.sleep(100);
                }
                Assert.state(documentIndex.countChunks(material.materialId(), null) == imported.chunks(),
                        "索引未在预期时间内完成片段写入: " + material.materialId());
                var repeated = importer.importMarkdown(resource, material);
                Assert.state(!repeated.imported(), "重复导入不应重新调用模型或新增片段: " + material.materialId());
                results.add(Map.of(
                        "materialId", material.materialId(),
                        "courseId", material.courseId(),
                        "contentHash", imported.contentHash(),
                        "chunks", imported.chunks(),
                        "imported", imported.imported(),
                        "repeatImportSkipped", true));
            }
            var report = Map.of(
                    "index", index,
                    "manifestVersion", manifest.version(),
                    "materials", results);
            Path reportPath = Path.of(args[0]);
            Files.writeString(reportPath, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("course material import report: " + reportPath.toAbsolutePath());
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        Assert.isTrue(StringUtils.hasText(value), "缺少环境变量: " + name);
        return value;
    }
}
