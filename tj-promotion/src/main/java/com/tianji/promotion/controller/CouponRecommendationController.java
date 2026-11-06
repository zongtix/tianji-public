package com.tianji.promotion.controller;

import com.tianji.promotion.domain.dto.CouponRecommendationDTO;
import com.tianji.promotion.domain.vo.CouponRecommendationVO;
import com.tianji.promotion.service.ICouponRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优惠券推荐接口。
 */
@Tag(name = "优惠券推荐")
@RestController
@RequiredArgsConstructor
@RequestMapping("/coupon-recommendations")
public class CouponRecommendationController {

    private final ICouponRecommendationService recommendationService;

    @Operation(summary = "为当前用户推荐优惠券使用方案",
            description = "根据课程价格、课程分类和当前用户真实持有且有效的用户券计算；返回用户券id、模板id、优惠总额和规则")
    @PostMapping
    public CouponRecommendationVO recommend(@RequestBody @Validated CouponRecommendationDTO dto) {
        return recommendationService.recommend(dto);
    }
}
