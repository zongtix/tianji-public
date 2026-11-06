-- 执行一次后，问答点赞消费者才能拒绝乱序到达的旧点赞数快照。
ALTER TABLE interaction_reply
    ADD COLUMN liked_version BIGINT NOT NULL DEFAULT 0 COMMENT '已应用的点赞数消息版本';
