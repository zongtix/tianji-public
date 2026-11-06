package com.tianji.learning.service.points;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.domain.vo.PointsBoardSeasonVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分榜赛季服务
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {
    Integer querySeasonByTime(LocalDateTime time);

    List<PointsBoardSeasonVO> querySeasonList();
}
