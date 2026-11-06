package com.tianji.learning.service.points.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.utils.BeanUtils;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.domain.vo.PointsBoardSeasonVO;
import com.tianji.learning.mapper.points.PointsBoardSeasonMapper;
import com.tianji.learning.mapper.points.PointsBoardMapper;
import com.tianji.common.constants.Constant;
import lombok.RequiredArgsConstructor;
import com.tianji.learning.service.points.IPointsBoardSeasonService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 积分榜赛季服务实现
 */
@Service
@RequiredArgsConstructor
public class PointsBoardSeasonServiceImpl extends ServiceImpl<PointsBoardSeasonMapper, PointsBoardSeason>
        implements IPointsBoardSeasonService {
    private final PointsBoardMapper boardMapper;
    @Override
    public Integer querySeasonByTime(LocalDateTime time) {
        // 赛季起止时间按日期存储，比较时去掉时分秒，确保结束日全天都能匹配。
        LocalDate date = time.toLocalDate();
        Optional<PointsBoardSeason> optional = lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, date)
                .ge(PointsBoardSeason::getEndTime, date)
                .oneOpt();
        return optional.map(PointsBoardSeason::getId).orElse(null);
    }

    @Override
    public List<PointsBoardSeasonVO> querySeasonList() {
        // 前端将最后一项作为当前赛季；历史赛季仅返回确实已有历史表的选项。
        LocalDate today = LocalDate.now();
        Set<String> tables = new HashSet<>(boardMapper.queryHistoryTables());
        List<PointsBoardSeason> seasons = lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, today)
                .orderByAsc(PointsBoardSeason::getBeginTime)
                .orderByAsc(PointsBoardSeason::getId)
                .list().stream()
                .filter(season -> !season.getEndTime().isBefore(today)
                        || tables.contains(Constant.SEASON_TABLE_NAME + season.getId()))
                .collect(Collectors.toList());
        return BeanUtils.copyList(seasons, PointsBoardSeasonVO.class);
    }
}
