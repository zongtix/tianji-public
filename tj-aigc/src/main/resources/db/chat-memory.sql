-- 在已有 tj_aigc 数据库执行；只新增消息记忆表，不修改会话表。
CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    message_index INT NOT NULL,
    message_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_position (conversation_id, message_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
