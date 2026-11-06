-- 首次切换到 Redis 写缓冲前执行一次。
-- 原唯一索引没有 biz_type，会把 QA/NOTE 中 id 相同的点赞对象错误地视为同一个对象。
ALTER TABLE liked_record
    DROP INDEX idx_biz_user,
    ADD UNIQUE KEY uk_user_biz_type (user_id, biz_type, biz_id),
    ADD KEY idx_biz_type_id (biz_type, biz_id);
