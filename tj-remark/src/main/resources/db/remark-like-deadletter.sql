CREATE TABLE IF NOT EXISTS liked_change_deadletter (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    stream_id VARCHAR(32) NOT NULL COMMENT '原始 Stream 记录 ID',
    payload TEXT NOT NULL COMMENT '原始字段键值对，JSON',
    fail_reason VARCHAR(128) NOT NULL COMMENT '解析失败原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stream_id (stream_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞变更死信，人工排查用';
