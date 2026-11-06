package com.tianji.aigc.memory;

import com.mongodb.ConnectionString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Records cleanup in the session transaction; remote deletion is retryable and idempotent. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "tj.ai.memory", name = "type", havingValue = "mongodb")
public class MongoMemoryCleanup {
    private final JdbcTemplate jdbc;
    private final ChatMemory memory;
    private final String storeId;

    public MongoMemoryCleanup(JdbcTemplate jdbc, ChatMemory memory,
                              @Value("${spring.data.mongodb.uri}") String mongoUri) {
        this.jdbc = jdbc;
        this.memory = memory;
        try {
            var connection = new ConnectionString(mongoUri);
            String identity = connection.isSrvProtocol() + "\n"
                    + String.join(",", connection.getHosts().stream().sorted().toList()) + "\n"
                    + connection.getDatabase() + "\n" + connection.getRequiredReplicaSetName();
            this.storeId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public void enqueue(String conversationId) {
        jdbc.update("INSERT INTO chat_memory_cleanup (store_id, conversation_id) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE store_id=store_id", storeId, conversationId);
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 60000)
    public void retryPending() {
        // 不同连接身份不能安全地指向当前库。告警后由运维连接原库处理，禁止自动重投。
        try {
            Long otherStores = jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_cleanup WHERE store_id <> ?", Long.class, storeId);
            if (otherStores != null && otherStores > 0) {
                log.warn("存在其他 MongoDB 存储身份的清理任务，须连接原库人工核对处理, currentStoreId={}, pending={}", storeId, otherStores);
            }
        } catch (RuntimeException error) {
            log.warn("检查 MongoDB 历史清理任务失败: {}", error.getClass().getSimpleName());
        }
        var pending = jdbc.queryForList("SELECT conversation_id FROM chat_memory_cleanup WHERE store_id = ? "
                + "ORDER BY last_attempt_at, conversation_id LIMIT 100", String.class, storeId);
        for (String conversationId : pending) {
            try {
                memory.clear(conversationId);
                jdbc.update("DELETE FROM chat_memory_cleanup WHERE store_id = ? AND conversation_id = ?", storeId, conversationId);
            } catch (RuntimeException error) {
                log.warn("MongoDB 会话记忆清理失败，将继续重试：{}", error.getClass().getSimpleName());
                try {
                    jdbc.update("UPDATE chat_memory_cleanup SET last_attempt_at = CURRENT_TIMESTAMP WHERE store_id = ? AND conversation_id = ?", storeId, conversationId);
                } catch (RuntimeException compensationError) {
                    // SQL 补偿失败也不能跳过本批剩余项；未删除的记录下次仍可重试。
                    log.warn("记录 MongoDB 清理重试时间失败，任务保留: {}", compensationError.getClass().getSimpleName());
                }
            }
        }
    }
}
