-- 启动本次修复后的 learning-service 前，在 tj_learning 执行。
CREATE TABLE IF NOT EXISTS points_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_key VARCHAR(128) NOT NULL COMMENT '稳定业务键；签到键同时用于防重复签到',
    routing_key VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    sent TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_key (message_key),
    KEY idx_sent_id (sent, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分消息发送记录';

-- 兼容已手动执行过幂等升级的数据库；没有执行过则补齐列和唯一索引。
SET @points_column_sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'points_record' AND column_name = 'biz_id'),
    'SELECT 1',
    'ALTER TABLE points_record ADD COLUMN biz_id VARCHAR(64) NULL COMMENT ''积分业务幂等键'''
);
PREPARE points_upgrade FROM @points_column_sql;
EXECUTE points_upgrade;
DEALLOCATE PREPARE points_upgrade;

-- 已有带 biz_id 的重复数据时 ADD UNIQUE 会报错；先核对重复积分再处理，不在迁移中自动删记录。
SET @points_index_sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'points_record' AND non_unique = 0
            GROUP BY index_name
            HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'user_id,type,biz_id'),
    'SELECT 1',
    'ALTER TABLE points_record ADD UNIQUE KEY uk_points_user_type_biz (user_id, type, biz_id)'
);
PREPARE points_upgrade FROM @points_index_sql;
EXECUTE points_upgrade;
DEALLOCATE PREPARE points_upgrade;

-- 本次为三类消息使用新队列 section.points.queue / note.points.queue / comment.points.queue，
-- 避免旧 learning.points.queue 遗留绑定继续把消息交给错误消费者。
-- 应用启动后自动解绑旧队列的上述三类 routing key，保留原有积压消息。
-- 旧队列历史积压需按 received routing key 分流后再清理，不可直接删除积压消息。
