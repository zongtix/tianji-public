package com.tianji.learning.mapper.points;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.learning.domain.po.PointsBoard;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 学霸天梯榜 Mapper 接口
 */
public interface PointsBoardMapper extends BaseMapper<PointsBoard> {
    void createPointsBoardTable(@Param("tableName") String tableName);

    void upsertHistoryBatch(@Param("boards") List<PointsBoard> boards);

    @Select("SELECT LOWER(table_name) FROM information_schema.tables " +
            "WHERE table_schema = SCHEMA() AND LOWER(table_name) LIKE 'points_board_season%'")
    List<String> queryHistoryTables();

    @Select("SELECT COUNT(*) FROM information_schema.tables " +
            "WHERE table_schema = SCHEMA() AND LOWER(table_name) = #{tableName}")
    int historyTableExists(@Param("tableName") String tableName);

    @Select("SELECT id, user_id, points FROM points_board WHERE id BETWEEN #{from} AND #{to} ORDER BY id")
    List<PointsBoard> queryHistoryRange(@Param("from") long from, @Param("to") long to);
}
