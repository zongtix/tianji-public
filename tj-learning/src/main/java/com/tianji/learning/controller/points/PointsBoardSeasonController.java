package com.tianji.learning.controller.points;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.learning.domain.vo.PointsBoardSeasonVO;
import com.tianji.learning.service.points.IPointsBoardSeasonService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分榜赛季控制器
 */
@Tag(name = "积分榜赛季相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/boards/seasons")
public class PointsBoardSeasonController {

    private final IPointsBoardSeasonService pointsBoardSeasonService;

    @Operation(summary = "查询已开始的积分榜赛季列表")
    @GetMapping("/list")
    public List<PointsBoardSeasonVO> querySeasonList() {
        return pointsBoardSeasonService.querySeasonList();
    }
}
