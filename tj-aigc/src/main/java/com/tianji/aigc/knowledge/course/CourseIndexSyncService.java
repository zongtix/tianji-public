package com.tianji.aigc.knowledge.course;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseIndexInfoDTO;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class CourseIndexSyncService {
    private final CourseClient courses;
    private final CourseIndexRepository repository;
    private final EmbeddingModel embedding;
    private final Clock clock;
    private final String model;
    private final int dimensions;
    private final RetryTemplate retries = RetryTemplate.builder().maxAttempts(3).fixedBackoff(200).build();

    public CourseIndexSyncService(CourseClient courses, CourseIndexRepository repository,
                                  EmbeddingModel embedding, Clock clock, String model, int dimensions) {
        Assert.isTrue("text-embedding-v4".equals(model) && dimensions == 1024, "课程索引模型或维度不匹配");
        this.courses = courses;
        this.repository = repository;
        this.embedding = embedding;
        this.clock = clock;
        this.model = model;
        this.dimensions = dimensions;
    }

    public Result synchronize(long courseId) throws IOException {
        Assert.isTrue(courseId > 0, "课程 ID 必须大于零");
        return retries.execute(context -> synchronizeOnce(courseId));
    }

    private Result synchronizeOnce(long courseId) throws IOException {
        // 顺序不能反过来：先拿版本，再读业务快照，CAS 冲突后整步重读。
        var old = repository.find(courseId);
        var snapshot = courses.getSemanticIndexInfo(courseId);
        Assert.state(snapshot == null || Long.valueOf(courseId).equals(snapshot.getId()), "课程快照 ID 不匹配");
        Document document = document(snapshot);
        String type = document == null ? "course_unavailable" : "course";
        String hash = document == null ? "unavailable" : document.getMetadata().get("contentHash").toString();
        if (old != null && old.source().get("metadata") instanceof Map<?, ?> metadata
                && type.equals(metadata.get("documentType")) && hash.equals(metadata.get("contentHash"))) {
            return Result.UNCHANGED;
        }
        String id = old == null ? UUID.nameUUIDFromBytes(("course-" + courseId)
                .getBytes(StandardCharsets.UTF_8)).toString() : old.id();
        var source = new LinkedHashMap<String, Object>();
        source.put("id", id);
        if (document == null) {
            source.put("content", "");
            source.put("metadata", Map.of("documentId", "course-" + courseId, "courseId", Long.toString(courseId),
                    "documentType", type, "contentHash", hash));
        } else {
            float[] vector = embedding.embed(document.getText());
            Assert.state(vector != null && vector.length == dimensions, "向量维度不匹配");
            double norm = 0;
            for (float value : vector) {
                Assert.state(Float.isFinite(value), "向量包含非有限值");
                norm += (double) value * value;
            }
            Assert.state(norm > 0, "向量不能全为零");
            source.put("content", document.getText());
            source.put("metadata", document.getMetadata());
            source.put("embedding", vector);
        }
        // 不直接删文档：保留不可检索的版本占位，阻止正在生成向量的旧请求复活课程。
        repository.write(old, source);
        return document == null ? Result.DISABLED : Result.UPDATED;
    }

    private Document document(CourseIndexInfoDTO snapshot) {
        if (snapshot == null) return null;
        Assert.state(snapshot.getDeleted() != null && snapshot.getStatus() != null, "课程快照缺少状态");
        var course = new CourseDocumentService.Course(snapshot.getId(), snapshot.getName(), snapshot.getStatus(),
                snapshot.getPurchaseStartTime(), snapshot.getPurchaseEndTime(), snapshot.getIntroduce(),
                snapshot.getUsePeople(), snapshot.getDetail(), snapshot.getCatalogue());
        if (snapshot.getDeleted() != 0 || !course.available(LocalDateTime.now(clock))) return null;
        try {
            return CourseDocumentService.toDocument(course, model, dimensions);
        } catch (IllegalArgumentException missingContent) {
            // 正式资料为空时不猜测正文，也不继续保留旧版本供推荐。
            return null;
        }
    }

    public enum Result { UPDATED, UNCHANGED, DISABLED }
}
