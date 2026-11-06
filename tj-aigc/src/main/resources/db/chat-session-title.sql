-- 在 tj_aigc 执行，允许重复执行。
-- 0=尚未提问，1=首轮已开始/临时标题，2=已完成自动标题或旧会话，3=用户手动命名。
-- 默认 2 保留所有旧会话的原标题；新建会话由应用显式写入 0。
SET @migration_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND COLUMN_NAME = 'title_status') = 0,
    'ALTER TABLE chat_session ADD COLUMN title_status TINYINT NOT NULL DEFAULT 2 COMMENT ''0=unstarted,1=first_turn,2=automatic_or_legacy,3=manual''',
    'SELECT ''title_status already exists'' AS migration_status'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
