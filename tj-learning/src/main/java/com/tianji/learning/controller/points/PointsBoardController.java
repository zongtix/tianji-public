package com.tianji.learning.controller.points;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.service.points.IPointsBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学霸天梯榜控制器
 */
@Tag(name = "积分榜相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/boards")
public class PointsBoardController {

    private final IPointsBoardService pointsBoardService;
    @Operation(summary = "分页查询指定赛季的积分排行榜")
    @GetMapping
    public PointsBoardVO queryPointsBoardBySeason(@Validated PointsBoardQuery query){
        return pointsBoardService.queryPointsBoardBySeason(query);
    }
}
