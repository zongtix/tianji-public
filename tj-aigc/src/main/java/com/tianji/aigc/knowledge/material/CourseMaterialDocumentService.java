package com.tianji.aigc.knowledge.material;

import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** 讲义 Markdown 切分与显式导入；稳定 ID 保证同内容重复导入不增副本。不注册为 Bean，避免误绑知识库 VectorStore。 */
public class CourseMaterialDocumentService {
    private final VectorStore vectorStore;
    private final KnowledgeDocumentIndex documentIndex;
    private final String embeddingModel;

    public CourseMaterialDocumentService(VectorStore vectorStore, KnowledgeDocumentIndex documentIndex,
            String embeddingModel) {
        this.vectorStore = vectorStore;
        this.documentIndex = documentIndex;
        this.embeddingModel = embeddingModel;
    }

    public ImportResult importMarkdown(Resource resource, CourseMaterialManifest.Material material) throws IOException {
        List<Document> chunks = readMarkdown(resource, material);
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        long conflicts = documentIndex.countChunks(material.materialId(), hash);
        Assert.isTrue(conflicts == 0, "该讲义已有其他版本，首期不自动覆盖，请使用新隔离索引验证");
        long existing = documentIndex.countChunks(material.materialId(), null);
        if (existing == chunks.size()) {
            return new ImportResult(material.materialId(), hash, chunks.size(), false);
        }
        vectorStore.add(chunks);
        return new ImportResult(material.materialId(), hash, chunks.size(), true);
    }

    public List<Document> readMarkdown(Resource resource, CourseMaterialManifest.Material material) throws IOException {
        Assert.notNull(material, "material 不能为空");
        Assert.isTrue(material.materialId() != null && material.materialId().matches("[a-zA-Z0-9_-]+"),
                "materialId 格式不正确");
        Assert.hasText(material.title(), "讲义标题不能为空");
        Assert.hasText(material.courseId(), "courseId 不能为空");
        String text;
        try (var input = resource.getInputStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        Assert.hasText(text, "讲义不能为空");
        String hash = sha256(text);
        var splitter = TokenTextSplitter.builder().withChunkSize(600)
                .withMinChunkSizeChars(100).withMinChunkLengthToEmbed(1).build();
        List<Document> chunks = new ArrayList<>();
        for (String section : text.split("(?m)(?=^## )")) {
            String body = section.strip();
            if (body.isEmpty()) continue;
            String heading = body.startsWith("## ")
                    ? body.lines().findFirst().orElseThrow().substring(3).strip()
                    : "文档说明";
            for (Document part : splitter.apply(List.of(new Document(body)))) {
                int chunkIndex = chunks.size() + 1;
                var metadata = new LinkedHashMap<String, Object>();
                metadata.put("courseId", material.courseId());
                metadata.put("materialId", material.materialId());
                // documentId 与 materialId 对齐，便于复用 KnowledgeDocumentIndex 按 documentId 计数。
                metadata.put("documentId", material.materialId());
                metadata.put("sourceId", material.materialId());
                metadata.put("title", material.title());
                metadata.put("headingPath", heading);
                metadata.put("chunkIndex", chunkIndex);
                metadata.put("contentHash", hash);
                metadata.put("embeddingModel", embeddingModel);
                String id = UUID.nameUUIDFromBytes((material.materialId() + ":" + hash + ":" + chunkIndex)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                chunks.add(new Document(id, material.title() + "\n章节：" + heading + "\n" + part.getText(), metadata));
            }
        }
        Assert.notEmpty(chunks, "讲义没有可导入的文本片段");
        return chunks;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public record ImportResult(String materialId, String contentHash, int chunks, boolean imported) { }
}
