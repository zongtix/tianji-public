package com.tianji.learning.mapper.points;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.learning.domain.po.PointsMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PointsMessageMapper extends BaseMapper<PointsMessage> {
    @Select("SELECT id, message_key, routing_key, payload, sent, create_time " +
            "FROM points_message WHERE sent = 0 ORDER BY id LIMIT 100")
    List<PointsMessage> queryPendingMessages();

    @Select("SELECT COUNT(*) FROM points_message WHERE sent = 0 AND routing_key = 'points.board.sync' " +
            "AND message_key LIKE CONCAT('board:', #{month}, ':%')")
    long countPendingBoardSync(@Param("month") String month);

    @Select("SELECT message_key FROM points_message " +
            "WHERE message_key LIKE CONCAT('sign:', #{userId}, ':', #{month}, '-%') ORDER BY message_key")
    List<String> querySignKeys(@Param("userId") Long userId, @Param("month") String month);

    @Update("UPDATE points_message SET sent = 1 WHERE id = #{id} AND sent = 0")
    int markSent(@Param("id") Long id);
}
