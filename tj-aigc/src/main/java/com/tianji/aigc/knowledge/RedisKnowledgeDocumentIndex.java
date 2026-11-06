package com.tianji.aigc.knowledge;

import org.springframework.util.Assert;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spring AI 之外的导入检查，始终只查询当前索引，不扫描 Redis 全库。 */
public class RedisKnowledgeDocumentIndex implements KnowledgeDocumentIndex {
    private final JedisPooled jedis;
    private final String indexName;
    private final String keyPrefix;

    public RedisKnowledgeDocumentIndex(JedisPooled jedis, String indexName) {
        this(jedis, indexName, "");
    }

    public RedisKnowledgeDocumentIndex(JedisPooled jedis, String indexName, String keyPrefix) {
        this.jedis = jedis;
        this.indexName = indexName;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public long countChunks(String documentId, String excludedContentHash) {
        Assert.isTrue(documentId != null && documentId.matches("[a-zA-Z0-9_-]+"), "文档 ID 格式不正确");
        String query = "@documentId:{" + escapeTag(documentId) + "}";
        if (excludedContentHash != null) {
            Assert.isTrue(excludedContentHash.matches("[a-f0-9]{64}"), "文档内容哈希格式不正确");
            query += " -@contentHash:{" + excludedContentHash + "}";
        }
        return jedis.ftSearch(indexName, new Query(query).limit(0, 0).dialect(2)).getTotalResults();
    }

    @Override
    public List<StoredChunk> listChunks(String documentId) {
        Assert.isTrue(documentId != null && documentId.matches("[a-zA-Z0-9_-]+"), "文档 ID 格式不正确");
        String query = "@documentId:{" + escapeTag(documentId) + "}";
        SearchResult result = jedis.ftSearch(indexName, new Query(query)
                .returnFields("content", "contentHash", "title", "chapter", "embeddingModel", "chunkIndex")
                .limit(0, MAX_LISTED_CHUNKS)
                .dialect(2));
        Assert.isTrue(result.getTotalResults() <= MAX_LISTED_CHUNKS
                        && result.getDocuments().size() == result.getTotalResults(),
                "文档片段数超过安全上限，拒绝自动布局核对与残留清理");
        List<StoredChunk> chunks = new ArrayList<>();
        for (redis.clients.jedis.search.Document hit : result.getDocuments()) {
            chunks.add(new StoredChunk(
                    documentIdFromKey(hit.getId()),
                    stringOrNull(hit, "contentHash"),
                    stringOrNull(hit, "title"),
                    stringOrNull(hit, "chapter"),
                    stringOrNull(hit, "embeddingModel"),
                    chunkIndexValue(hit.get("chunkIndex")), stringOrNull(hit, "content")));
        }
        return List.copyOf(chunks);
    }

    public void validateSchema(String prefix, int dimensions) {
        Map<String, Object> info = jedis.ftInfo(indexName);
        Map<String, Object> definition = fields(info.get("index_definition"));
        List<?> prefixes = (List<?>) definition.get("prefixes");
        Assert.isTrue("JSON".equals(text(definition.get("key_type"))) && prefixes != null
                        && prefixes.size() == 1 && prefix.equals(text(prefixes.get(0))),
                "Redis 知识库索引的类型或 key 前缀不匹配，请使用新索引，不要覆盖已有数据");
        Map<String, Map<String, Object>> attributes = new LinkedHashMap<>();
        for (Object value : (List<?>) info.get("attributes")) {
            var field = fields(value);
            attributes.put(text(field.get("attribute")), field);
        }
        var vector = attributes.getOrDefault("embedding", Map.of());
        Assert.isTrue("$.embedding".equals(text(vector.get("identifier")))
                        && "VECTOR".equals(text(vector.get("type")))
                        && "HNSW".equals(text(vector.get("algorithm")))
                        && "FLOAT32".equals(text(vector.get("data_type")))
                        && String.valueOf(dimensions).equals(text(vector.get("dim")))
                        && "COSINE".equals(text(vector.get("distance_metric"))),
                "Redis 知识库索引的向量维度或相似度不匹配，请使用新索引，不要覆盖已有数据");
        Map.of("content", "TEXT", "documentId", "TAG", "contentHash", "TAG", "title", "TEXT",
                "chapter", "TEXT", "source", "TAG", "chunkIndex", "NUMERIC", "embeddingModel", "TAG")
                .forEach((name, type) -> {
                    var field = attributes.getOrDefault(name, Map.of());
                    Assert.isTrue(type.equals(text(field.get("type")))
                                    && ("$." + name).equals(text(field.get("identifier"))),
                            "Redis 知识库索引缺少正确的元数据字段：" + name);
                });
    }

    private String documentIdFromKey(String redisKey) {
        if (redisKey == null) {
            return null;
        }
        if (!keyPrefix.isEmpty() && redisKey.startsWith(keyPrefix)) {
            return redisKey.substring(keyPrefix.length());
        }
        return redisKey;
    }

    private static String escapeTag(String value) {
        return value.replace("-", "\\-");
    }

    private static String stringOrNull(redis.clients.jedis.search.Document hit, String field) {
        if (!hit.hasProperty(field)) {
            return null;
        }
        String value = hit.getString(field);
        return value == null || value.isBlank() ? null : value;
    }

    private static Object chunkIndexValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number;
        }
        String text = String.valueOf(raw).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    // Jedis 的 FT.INFO 返回值包含交错键值列表；兼容其已转换为 Map 的部分。
    private static Map<String, Object> fields(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(text(key), item));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i + 1 < list.size(); i += 2) result.put(text(list.get(i)), list.get(i + 1));
        }
        return result;
    }

    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }
}
