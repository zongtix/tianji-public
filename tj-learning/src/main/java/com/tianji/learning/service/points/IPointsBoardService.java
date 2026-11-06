package com.tianji.learning.service.points;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;

import java.util.List;

/**
 * 学霸天梯榜服务
 */
public interface IPointsBoardService extends IService<PointsBoard> {
    PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query);

    void createPointsBoardTable(Integer season);

    //实现查询当前赛季榜单
    List<PointsBoard> queryCurrentBoardList(String key, Integer pageNo, Integer pageSize);

    void updateBoardScore(Long userId, String month, int points);

    void saveHistoryBatch(List<PointsBoard> boards);

    /** 清理前完整比较归档与 Redis 的排名、用户、积分；必须在归档写锁内调用。 */
    void verifyHistoryBoard(Integer season, String key);
}
