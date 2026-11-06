package com.tianji.aigc.knowledge;

import java.io.IOException;
import java.util.List;

/**
 * 导入前检查存储中的片段；excludedContentHash 为 null 时统计全部，否则仅统计不同版本。
 * listChunks 用于布局核对与按真实旧 ID 清理；默认方法保留函数式接口兼容（lambda 仅计数场景）。
 */
@FunctionalInterface
public interface KnowledgeDocumentIndex {
    /** 单文档枚举安全上限；超出则拒绝自动 skip/清理，避免截断漏删。 */
    int MAX_LISTED_CHUNKS = 512;

    long countChunks(String documentId, String excludedContentHash) throws IOException;

    /**
     * 仅按 documentId 枚举当前索引内片段（含 id 与布局元数据），不扫全库。
     * 实现须遵守 {@link #MAX_LISTED_CHUNKS}；截断时抛错而非静默少返回。
     */
    default List<StoredChunk> listChunks(String documentId) throws IOException {
        throw new UnsupportedOperationException("当前 KnowledgeDocumentIndex 未实现 listChunks");
    }

    /** 导入布局核对与精确删除用；缺字段视为坏行，不得当成完整一致。 */
    record StoredChunk(
            String id,
            String contentHash,
            String title,
            String chapter,
            String embeddingModel,
            Object chunkIndex,
            String content) {
        boolean hasUsableId() {
            return id != null && !id.isBlank();
        }

        boolean layoutComplete() {
            return hasUsableId() && contentHash != null && title != null && chapter != null
                    && embeddingModel != null && chunkIndex != null && content != null;
        }
    }
}
