package com.tianji.promotion.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 优惠券推荐请求。
 * 只接收要购买的课程，课程价格与分类由课程服务提供，避免客户端伪造金额和范围。
 */
@Data
@Schema(description = "优惠券推荐请求")
public class CouponRecommendationDTO {

    @Schema(description = "要购买的课程id列表，一次最多10门", example = "[101, 102]")
    @NotNull(message = "请选择要购买的课程")
    @Size(min = 1, max = 10, message = "一次最多选购10门课程")
    private List<@NotNull(message = "课程id不能为空") Long> courseIds;
}
