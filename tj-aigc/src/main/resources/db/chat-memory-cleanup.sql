-- MongoDB 记忆模式启用前，在 tj_aigc 的 MySQL 数据库执行；不修改已有会话和消息。
CREATE TABLE IF NOT EXISTS chat_memory_cleanup (
    store_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    last_attempt_at TIMESTAMP(6) NULL,
    PRIMARY KEY (store_id, conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
