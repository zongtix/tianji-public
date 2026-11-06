package com.tianji.aigc.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 首期只导入可信 Markdown；稳定 ID 保证同一份内容重复导入不会增加副本。 */
@Service
@ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeDocumentService {
    private final VectorStore vectorStore;
    private final KnowledgeDocumentIndex documentIndex;
    private final String embeddingModel;

    public KnowledgeDocumentService(VectorStore vectorStore, KnowledgeDocumentIndex documentIndex,
                                    @Value("${tj.ai.knowledge.embedding.model}") String embeddingModel) {
        this.vectorStore = vectorStore;
        this.documentIndex = documentIndex;
        this.embeddingModel = embeddingModel;
    }

    public ImportResult importMarkdown(Resource resource, String documentId, String title) throws IOException {
        List<Document> chunks = readMarkdown(resource, documentId, title);
        String hash = (String) chunks.get(0).getMetadata().get("contentHash");
        long conflicts = documentIndex.countChunks(documentId, hash);
        Assert.isTrue(conflicts == 0, "该文档已有其他版本，首期不自动覆盖，请先明确文档更新策略");
        // 按真实旧片段布局核对：同 hash 仅 count 相等仍可能标题/模型/边界不同，或 chunkIndex 稀疏。
        List<KnowledgeDocumentIndex.StoredChunk> existing = documentIndex.listChunks(documentId);
        if (sameLayout(existing, chunks)) {
            return new ImportResult(documentId, hash, chunks.size(), false);
        }
        // 若上次只写入一部分，同样的稳定 ID 会覆盖已有片段并补齐缺少的片段。
        vectorStore.add(chunks);
        // 写入成功后再按枚举出的旧 ID 删残留；失败路径不删，合法旧证据保留。
        // 不按 count 推导连续 index——稀疏空洞会导致漏删。
        Set<String> keepIds = new HashSet<>();
        for (Document chunk : chunks) {
            keepIds.add(chunk.getId());
        }
        List<String> staleIds = new ArrayList<>();
        for (KnowledgeDocumentIndex.StoredChunk stored : existing) {
            if (stored.hasUsableId() && !keepIds.contains(stored.id())) {
                staleIds.add(stored.id());
            }
        }
        if (!staleIds.isEmpty()) {
            vectorStore.delete(staleIds);
        }
        return new ImportResult(documentId, hash, chunks.size(), true);
    }

    public List<Document> readMarkdown(Resource resource, String documentId, String title) throws IOException {
        Assert.isTrue(documentId != null && documentId.matches("[a-zA-Z0-9_-]+"), "文档 ID 格式不正确");
        Assert.hasText(title, "文档标题不能为空");
        String text;
        try (var input = resource.getInputStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        Assert.hasText(text, "文档不能为空");
        String hash = sha256(text);
        var splitter = TokenTextSplitter.builder().withChunkSize(600)
                .withMinChunkSizeChars(100).withMinChunkLengthToEmbed(1).build();
        List<Document> chunks = new ArrayList<>();
        for (String section : text.split("(?m)(?=^## )")) {
            String body = section.strip();
            if (body.isEmpty()) continue;
            String heading = body.startsWith("## ") ? body.lines().findFirst().orElseThrow().substring(3).strip() : "文档说明";
            for (Document part : splitter.apply(List.of(new Document(body)))) {
                int chunkIndex = chunks.size() + 1;
                var metadata = new LinkedHashMap<String, Object>();
                metadata.put("documentId", documentId);
                metadata.put("title", title);
                metadata.put("source", resource.getFilename());
                metadata.put("chapter", heading);
                metadata.put("chunkIndex", chunkIndex);
                metadata.put("contentHash", hash);
                metadata.put("embeddingModel", embeddingModel);
                String id = stableChunkId(documentId, hash, chunkIndex);
                chunks.add(new Document(id, title + "\n章节：" + heading + "\n" + part.getText(), metadata));
            }
        }
        Assert.notEmpty(chunks, "文档没有可导入的文本片段");
        return chunks;
    }

    /** 内容 hash 相同不代表布局相同：须 id 集合一致且 title/chapter/model/index 对齐；坏行不得 skip。 */
    static boolean sameLayout(List<KnowledgeDocumentIndex.StoredChunk> existing, List<Document> expected) {
        if (existing.size() != expected.size()) {
            return false;
        }
        var byId = new LinkedHashMap<String, KnowledgeDocumentIndex.StoredChunk>();
        for (KnowledgeDocumentIndex.StoredChunk stored : existing) {
            if (!stored.layoutComplete() || byId.put(stored.id(), stored) != null) {
                return false;
            }
        }
        for (Document chunk : expected) {
            KnowledgeDocumentIndex.StoredChunk stored = byId.get(chunk.getId());
            if (stored == null) {
                return false;
            }
            var meta = chunk.getMetadata();
            if (!Objects.equals(stored.content(), chunk.getText())
                    || !Objects.equals(stored.contentHash(), meta.get("contentHash"))
                    || !Objects.equals(stored.title(), meta.get("title"))
                    || !Objects.equals(stored.chapter(), meta.get("chapter"))
                    || !Objects.equals(stored.embeddingModel(), meta.get("embeddingModel"))
                    || !chunkIndexEquals(stored.chunkIndex(), meta.get("chunkIndex"))) {
                return false;
            }
        }
        return true;
    }

    static boolean chunkIndexEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return leftNumber.longValue() == rightNumber.longValue();
        }
        return Objects.equals(String.valueOf(left), String.valueOf(right));
    }

    static String stableChunkId(String documentId, String hash, long chunkIndex) {
        return UUID.nameUUIDFromBytes((documentId + ":" + hash + ":" + chunkIndex)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public record ImportResult(String documentId, String contentHash, int chunks, boolean imported) { }
}
