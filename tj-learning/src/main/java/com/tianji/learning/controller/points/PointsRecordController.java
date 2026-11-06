package com.tianji.learning.controller.points;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.service.points.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学习积分记录控制器
 */
@Tag(name = "积分记录相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/points")
public class PointsRecordController {

    private final IPointsRecordService pointsRecordService;

    /**
     * 查询今日积分情况。
     * <p>
     * 路径必须是 /points/today —— 前端 src/api/class.js 的 getTodayPoints 就按它调，
     * 原来这个类写的 @RequestMapping("/points-records") 对不上（这也是它一直只能当空壳的原因）。
     */
    @GetMapping("/today")
    @Operation(summary = "查询今日积分情况")
    public List<PointsStatisticsVO> queryTodayPoints() {
        return pointsRecordService.queryMyTodayPoints();
    }
}
