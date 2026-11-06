-- 在 tj_aigc 执行。只新增可空字段，旧会话保持普通聊天；允许重复执行。
SET @migration_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND COLUMN_NAME = 'knowledge_base_id') = 0,
    'ALTER TABLE chat_session ADD COLUMN knowledge_base_id VARCHAR(64) NULL COMMENT ''创建时绑定的知识库，NULL为普通聊天''',
    'SELECT ''knowledge_base_id already exists'' AS migration_status'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
