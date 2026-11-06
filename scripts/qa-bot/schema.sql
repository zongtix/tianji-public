-- 在 tj_learning 库执行；不修改现有问题、评论或积分表。
CREATE TABLE IF NOT EXISTS bot_reply_task (
    source_reply_id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response_reply_id BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bot_task_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
