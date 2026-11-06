CREATE TABLE IF NOT EXISTS coupon_claim_request (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(16) NOT NULL,
    coupon_id BIGINT NULL,
    exchange_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) NOT NULL,
    user_coupon_id BIGINT NULL,
    message VARCHAR(255) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_process_time DATETIME(6) NOT NULL,
    next_publish_time DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_claim_user_key (user_id, idempotency_key),
    KEY idx_claim_publish (status, next_publish_time, next_process_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
