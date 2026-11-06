-- 启用长期记忆前，在 tj_aigc 数据库执行。正文和待提取输入均为应用加密密文。
CREATE TABLE IF NOT EXISTS user_memory_profile (
    user_id BIGINT NOT NULL PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    epoch BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_memory_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    memory_key VARCHAR(32) NOT NULL,
    content_cipher TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    source_task_id BIGINT NOT NULL,
    source_session_id VARCHAR(32) NOT NULL,
    source_request_id CHAR(36) NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_memory_user_key (user_id, memory_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_memory_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(32) NOT NULL,
    epoch BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    payload_cipher TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    ready_at DATETIME NULL,
    claim_token CHAR(36) NULL,
    claim_until DATETIME NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NULL,
    UNIQUE KEY uk_memory_request (request_id),
    KEY idx_memory_task_state (state, next_attempt_at, ready_at, id),
    KEY idx_memory_task_created (state, created_at),
    KEY idx_memory_task_lease (state, claim_until),
    KEY idx_memory_task_user (user_id, session_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
