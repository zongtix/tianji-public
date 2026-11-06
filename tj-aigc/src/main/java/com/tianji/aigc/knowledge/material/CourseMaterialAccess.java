package com.tianji.aigc.knowledge.material;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/** 服务端按 manifest 计算讲义检索范围；模型参数不能扩大权限。 */
public class CourseMaterialAccess {
    public enum Status { OK, ACCESS_DENIED, TEMPORARILY_UNAVAILABLE }

    private static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[a-zA-Z0-9_-]+");

    private final CourseMaterialManifest manifest;

    public CourseMaterialAccess(CourseMaterialManifest manifest) {
        Assert.notNull(manifest, "manifest 不能为空");
        this.manifest = manifest;
    }

    public static CourseMaterialAccess fromClasspath(String path) {
        return new CourseMaterialAccess(CourseMaterialManifest.fromClasspath(path));
    }

    public Decision authorize(Long userId, String courseId) {
        if (userId == null || !StringUtils.hasText(courseId)) {
            return Decision.denied();
        }
        List<CourseMaterialManifest.Material> allowed = manifest.materials().stream()
                .filter(m -> courseId.equals(m.courseId()))
                .filter(m -> "PUBLIC_DEMO".equals(m.access()))
                .filter(m -> "PUBLISHED".equals(m.publishStatus()))
                .toList();
        if (allowed.isEmpty()) {
            return Decision.denied();
        }
        // manifest 受控，但仍与知识库一致校验字符集，避免异常 ID 污染过滤表达式。
        Assert.isTrue(SAFE_ID.matcher(courseId).matches(), "课程 ID 格式不正确");
        String materialIds = allowed.stream()
                .map(CourseMaterialManifest.Material::materialId)
                .map(CourseMaterialAccess::quoteSafeId)
                .collect(Collectors.joining(","));
        String filter = "courseId == '" + courseId + "' && materialId in [" + materialIds + "]";
        String courseName = allowed.get(0).courseName();
        return new Decision(Status.OK, filter, courseId, courseName, allowed);
    }

    private static String quoteSafeId(String id) {
        Assert.isTrue(id != null && SAFE_ID.matcher(id).matches(), "讲义 ID 格式不正确");
        return "'" + id + "'";
    }

    public record Decision(
            Status status,
            String filterExpression,
            String courseId,
            String courseName,
            List<CourseMaterialManifest.Material> materials) {
        public static Decision denied() {
            return new Decision(Status.ACCESS_DENIED, null, null, null, List.of());
        }
    }
}
