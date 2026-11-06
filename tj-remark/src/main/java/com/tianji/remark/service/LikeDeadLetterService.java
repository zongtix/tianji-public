package com.tianji.remark.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 隔离无法解析的 Redis Stream 记录，保存原始字段 JSON 和原因到 liked_change_deadletter。
 * 由 LikeWriteBehindTask 调用；只有保存成功，坏记录才进入可 ACK 列表，避免直接丢弃诊断信息。
 * stream_id 唯一键和重复键无操作支持重复保存；这是本地数据库隔离表，区别于 RabbitMQ 死信队列。
 */
@Service
@RequiredArgsConstructor
public class LikeDeadLetterService {
    private static final String INSERT_SQL =
            "INSERT INTO liked_change_deadletter (stream_id, payload, fail_reason) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE id = id";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 记录 Stream ID、原始载荷和解析错误；序列化或写库失败会交回调用方保留原记录并退避重试。
     */
    public void save(MapRecord<String, Object, Object> record, String reason) throws JsonProcessingException {
        jdbcTemplate.update(INSERT_SQL, record.getId().getValue(),
                objectMapper.writeValueAsString(record.getValue()), reason);
    }
}
