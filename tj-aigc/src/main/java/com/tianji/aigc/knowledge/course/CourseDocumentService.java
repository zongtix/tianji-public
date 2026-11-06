package com.tianji.aigc.knowledge.course;

import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 显式导入课程正文；索引只找候选，课程可用性始终重新查询业务库。 */
public class CourseDocumentService {
    private final JdbcTemplate jdbc;
    private final VectorStore vectors;
    private final KnowledgeDocumentIndex index;
    private final String embeddingModel;
    private final int dimensions;
    private final Clock clock;

    public CourseDocumentService(JdbcTemplate jdbc, VectorStore vectors, KnowledgeDocumentIndex index,
                                 String embeddingModel, int dimensions, Clock clock) {
        Assert.hasText(embeddingModel, "向量模型不能为空");
        Assert.isTrue(dimensions > 0, "向量维度必须大于零");
        this.jdbc = jdbc;
        this.vectors = vectors;
        this.index = index;
        this.embeddingModel = embeddingModel;
        this.dimensions = dimensions;
        this.clock = clock;
    }

    public Optional<Course> findCourse(long courseId) {
        Assert.isTrue(courseId > 0, "课程 ID 必须大于零");
        var courses = jdbc.query("""
                SELECT c.id, c.name, c.status, c.purchase_start_time, c.purchase_end_time,
                       cc.course_introduce, cc.use_people, cc.course_detail
                FROM course c LEFT JOIN course_content cc ON cc.id = c.id AND cc.deleted = 0
                WHERE c.id = ? AND c.deleted = 0
                """, (rs, row) -> new Course(rs.getLong("id"), rs.getString("name"),
                rs.getInt("status"), time(rs, "purchase_start_time"), time(rs, "purchase_end_time"),
                rs.getString("course_introduce"), rs.getString("use_people"), rs.getString("course_detail")),
                courseId);
        if (courses.isEmpty()) return Optional.empty();
        Course course = courses.get(0);
        List<String> catalogue = jdbc.queryForList("""
                SELECT CASE WHEN cc.type=1 THEN cc.name ELSE CONCAT(ch.name,' / ',cc.name) END AS title
                FROM course_catalogue cc
                LEFT JOIN course_catalogue ch ON ch.id=cc.parent_catalogue_id
                    AND ch.course_id=cc.course_id AND ch.type=1 AND ch.deleted=0 AND ch.parent_catalogue_id=0
                WHERE cc.course_id=? AND cc.deleted=0 AND TRIM(cc.name)<>''
                  AND ((cc.type=1 AND cc.parent_catalogue_id=0)
                       OR (cc.type=2 AND ch.id IS NOT NULL AND TRIM(ch.name)<>''))
                ORDER BY cc.course_id,COALESCE(ch.c_index,cc.c_index),COALESCE(ch.id,cc.id),cc.type,cc.c_index,cc.id
                """, String.class, courseId);
        return Optional.of(new Course(course.id(), course.name(), course.status(), course.purchaseStart(),
                course.purchaseEnd(), course.intro(), course.audience(), course.detail(), catalogue));
    }

    public ImportResult importCourse(long courseId) throws IOException {
        Course course = findCourse(courseId).orElseThrow(() -> new IllegalArgumentException("课程不存在：" + courseId));
        String documentId = "course-" + courseId;
        if (!course.available(LocalDateTime.now(clock))) {
            return new ImportResult(Long.toString(courseId), documentId, "UNAVAILABLE", null);
        }
        Document document = toDocument(course);
        String hash = (String) document.getMetadata().get("contentHash");
        Assert.isTrue(index.countChunks(documentId, hash) == 0,
                "课程已有不同正文或向量模型版本，请明确更新策略后再导入：" + courseId);
        long count = index.countChunks(documentId, null);
        Assert.isTrue(count <= 1, "课程索引存在重复文档，请先核对：" + courseId);
        if (count == 1) return new ImportResult(Long.toString(courseId), documentId, "UNCHANGED", hash);
        vectors.add(List.of(document));
        return new ImportResult(Long.toString(courseId), documentId, "IMPORTED", hash);
    }

    public Document toDocument(Course course) {
        return toDocument(course, embeddingModel, dimensions);
    }

    public static Document toDocument(Course course, String embeddingModel, int dimensions) {
        Assert.isTrue(course.id() > 0, "课程 ID 必须大于零");
        String name = plainText(course.name());
        String intro = plainText(course.intro());
        String audience = plainText(course.audience());
        String detail = plainText(course.detail());
        Assert.hasText(name, "课程名称不能为空");
        Assert.isTrue(StringUtils.hasText(intro) || StringUtils.hasText(detail), "课程正文缺失：" + course.id());
        String text = "课程名称：" + name + "\n课程介绍：" + intro
                + "\n适用人群：" + audience + "\n课程详情与大纲：" + detail;
        List<String> catalogue = course.catalogue() == null ? List.of() : course.catalogue().stream()
                .map(CourseDocumentService::plainText).filter(StringUtils::hasText).toList();
        if (!catalogue.isEmpty()) {
            text += "\n正式章节目录（仅标题）：\n" + String.join("\n", catalogue);
        }
        String hash = sha256(embeddingModel + "\n" + dimensions + "\n" + text);
        String documentId = "course-" + course.id();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("documentId", documentId);
        metadata.put("courseId", Long.toString(course.id()));
        metadata.put("documentType", "course");
        metadata.put("title", name);
        metadata.put("source", catalogue.isEmpty() ? "tj_course.course+course_content"
                : "tj_course.course+course_content+course_catalogue");
        metadata.put("contentHash", hash);
        metadata.put("embeddingModel", embeddingModel);
        metadata.put("embeddingDimensions", dimensions);
        String id = UUID.nameUUIDFromBytes((documentId + ":" + hash).getBytes(StandardCharsets.UTF_8)).toString();
        return new Document(id, text, metadata);
    }

    public List<Match> search(String query, List<Long> courseIds) {
        Assert.hasText(query, "检索问题不能为空");
        Assert.isTrue(query.length() <= 4000, "检索问题过长");
        Assert.notEmpty(courseIds, "检索课程范围不能为空");
        Assert.isTrue(courseIds.size() <= 100 && courseIds.stream().allMatch(id -> id != null && id > 0),
                "检索课程范围不正确");
        String filter = "courseId in [" + String.join(",", courseIds.stream().distinct()
                .map(id -> "'" + id + "'").toList()) + "]";
        var documents = vectors.similaritySearch(SearchRequest.builder().query(query.strip()).topK(3)
                .similarityThreshold(0.6).filterExpression(filter).build());
        var matches = new ArrayList<Match>();
        for (Document document : documents) {
            Object value = document.getMetadata().get("courseId");
            Assert.state(value instanceof String && ((String) value).matches("[1-9][0-9]{0,18}"),
                    "课程索引缺少合法课程 ID");
            long courseId = Long.parseLong((String) value);
            // 即使索引尚未更新，也不返回后来被删除、下架或到期的课程。
            if (!courseIds.contains(courseId)) continue;
            findCourse(courseId).filter(course -> course.available(LocalDateTime.now(clock)))
                    .ifPresent(course -> matches.add(new Match(Long.toString(courseId), course.name(), document.getScore())));
        }
        return matches;
    }

    private static String plainText(String value) {
        if (value == null) return "";
        String text = value.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("(?s)<[^>]*>", " ");
        return HtmlUtils.htmlUnescape(text).replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public record Course(long id, String name, int status, LocalDateTime purchaseStart,
                         LocalDateTime purchaseEnd, String intro, String audience, String detail, List<String> catalogue) {
        public Course(long id, String name, int status, LocalDateTime purchaseStart,
                      LocalDateTime purchaseEnd, String intro, String audience, String detail) {
            this(id, name, status, purchaseStart, purchaseEnd, intro, audience, detail, List.of());
        }
        public boolean available(LocalDateTime now) {
            return (status == 2 || status == 4)
                    && (purchaseStart == null || !purchaseStart.isAfter(now))
                    && (purchaseEnd == null || purchaseEnd.isAfter(now));
        }
    }

    public record ImportResult(String courseId, String documentId, String status, String contentHash) { }
    public record Match(String courseId, String name, Double score) { }
}
