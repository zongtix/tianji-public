-- 已存在 V1 表时执行一次；新库只执行 user-memory.sql。
-- 先停止所有 AIGC 实例的记忆读写，备份后执行，再启动多事实版本。
-- 保留已有记忆的 ID、密文、版本和来源，不自动拆分或删除旧摘要。
-- 不可直接回滚旧版本：同类出现多行后，旧版会按类别覆盖数据。
ALTER TABLE user_memory_item
    ADD INDEX idx_memory_user_key (user_id, memory_key, id);
ALTER TABLE user_memory_item
    DROP INDEX uk_memory_user_key;
