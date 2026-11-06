package com.tianji.aigc.knowledge.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** 服务端讲义发布清单；访问级别与发布状态以本文件为准，不信任 ES 元数据。 */
public record CourseMaterialManifest(String version, List<Material> materials) {

    public record Material(
            String materialId,
            String courseId,
            String courseName,
            String title,
            String sourceFile,
            String access,
            String publishStatus,
            String origin,
            String verifiedAt) {
    }

    public static CourseMaterialManifest fromClasspath(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            CourseMaterialManifest manifest = new ObjectMapper().readValue(in, CourseMaterialManifest.class);
            Assert.hasText(manifest.version(), "manifest.version 不能为空");
            Assert.notEmpty(manifest.materials(), "manifest.materials 不能为空");
            for (Material material : manifest.materials()) {
                Assert.hasText(material.materialId(), "materialId 不能为空");
                Assert.hasText(material.courseId(), "courseId 不能为空");
                Assert.hasText(material.sourceFile(), "sourceFile 不能为空");
                Assert.hasText(material.access(), "access 不能为空");
                Assert.hasText(material.publishStatus(), "publishStatus 不能为空");
            }
            return manifest;
        } catch (IOException error) {
            throw new IllegalStateException("无法读取讲义 manifest: " + path, error);
        }
    }
}
