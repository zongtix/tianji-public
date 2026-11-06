package com.tianji.aigc.knowledge.course;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.util.Assert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次性课程导入入口；不启动 Web、Nacos、聊天模型或业务服务。配置和凭据仅通过 stdin 输入。 */
public class CourseImportCommand {
    public static final String COURSE_INDEX = "tj_aigc_courses_v4_1024";

    public static void main(String[] args) throws Exception {
        Assert.isTrue(args.length == 1, "请指定报告文件路径");
        ObjectMapper json = new ObjectMapper();
        Input input = json.readValue(System.in, Input.class);
        Assert.notEmpty(input.courseIds(), "请明确指定课程 ID");
        Assert.isTrue(input.courseIds().size() <= 100
                && input.courseIds().stream().allMatch(id -> id != null && id > 0), "课程 ID 范围不正确");
        Assert.notEmpty(input.probes(), "请提供检索验收问题");
        Assert.isTrue(input.probes().stream().allMatch(probe -> probe != null
                && input.courseIds().contains(probe.expectedCourseId())), "验收目标必须在导入范围内");
        Assert.isTrue(input.jdbcUrl() != null && input.jdbcUrl().matches("jdbc:mysql://[^/]+/tj_course(?:\\?.*)?"),
                "只允许读取现有 tj_course 数据库");
        var properties = new LinkedHashMap<>(input.properties());
        properties.put("tj.ai.knowledge.enabled", true);
        properties.put("tj.ai.knowledge.store-type", "elasticsearch");
        properties.put("tj.ai.knowledge.index-name", COURSE_INDEX);
        int dimensions = Integer.parseInt(properties.get("tj.ai.knowledge.embedding.dimensions").toString());
        Assert.isTrue(dimensions == 1024
                && "text-embedding-v4".equals(properties.get("tj.ai.knowledge.embedding.model")),
                "当前课程索引只允许 text-embedding-v4 / 1024 维，其他模型请使用独立新索引");
        try (var connection = DriverManager.getConnection(input.jdbcUrl(), input.username(), input.password());
             var context = new AnnotationConfigApplicationContext()) {
            connection.setReadOnly(true);
            Assert.state("tj_course".equals(connection.getCatalog()), "实际连接的数据库不正确");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("course-import", properties));
            context.register(JacksonAutoConfiguration.class, KnowledgeStoreConfig.class,
                    ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class);
            context.refresh();
            var index = context.getBean(KnowledgeDocumentIndex.class);
            var service = new CourseDocumentService(new JdbcTemplate(new SingleConnectionDataSource(connection, true)),
                    context.getBean(VectorStore.class), index,
                    properties.get("tj.ai.knowledge.embedding.model").toString(), dimensions,
                    Clock.system(ZoneId.of("Asia/Shanghai")));
            List<Long> courseIds = input.courseIds().stream().distinct().toList();
            // 在第一次付费向量调用前核对所选课程确实存在。
            for (long id : courseIds) Assert.state(service.findCourse(id).isPresent(), "课程不存在：" + id);
            var imports = new ArrayList<CourseDocumentService.ImportResult>();
            for (long id : courseIds) imports.add(service.importCourse(id));
            for (var imported : imports) {
                if (imported.status().equals("UNAVAILABLE")) continue;
                awaitDocument(index, imported.documentId());
                Assert.state(service.importCourse(Long.parseLong(imported.courseId())).status().equals("UNCHANGED"),
                        "重复导入未跳过：" + imported.courseId());
            }
            var checks = new ArrayList<Map<String, Object>>();
            boolean passed = true;
            for (Probe probe : input.probes()) {
                var hits = service.search(probe.query(), courseIds);
                boolean matched = hits.stream().anyMatch(hit -> hit.courseId().equals(probe.expectedCourseId().toString()));
                passed &= matched;
                checks.add(Map.of("question", probe.query(), "expectedCourseId", probe.expectedCourseId().toString(),
                        "passed", matched, "hits", hits));
            }
            var report = Map.of("index", COURSE_INDEX, "embeddingModel", "text-embedding-v4", "dimensions", dimensions,
                    "imports", imports, "repeatImportSkipped", true, "retrievalChecks", checks, "passed", passed);
            Path reportPath = Path.of(args[0]);
            Files.createDirectories(reportPath.toAbsolutePath().getParent());
            Files.writeString(reportPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            Assert.state(passed, "课程检索验收未全部命中，请查看报告");
            System.out.println("COURSE_IMPORT_OK courses=" + imports.size() + " available="
                    + imports.stream().filter(item -> !item.status().equals("UNAVAILABLE")).count()
                    + " probes=" + checks.size() + " repeatImportSkipped=true");
        }
    }

    private static void awaitDocument(KnowledgeDocumentIndex index, String documentId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (index.countChunks(documentId, null) == 1) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("课程索引未及时可见：" + documentId);
    }

    public record Probe(String query, Long expectedCourseId) { }
    public record Input(Map<String, Object> properties, String jdbcUrl, String username, String password,
                        List<Long> courseIds, List<Probe> probes) { }
}
