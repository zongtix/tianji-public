package com.tianji.promotion.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 优惠券推荐结果。列表顺序即建议的核销顺序。
 */
@Data
@Schema(description = "优惠券推荐结果")
public class CouponRecommendationVO {

    @Schema(description = "订单课程原始金额，单位分")
    private Integer originalAmount;
    @Schema(description = "优惠总额，单位分")
    private Integer totalDiscount;
    @Schema(description = "优惠后应付金额，单位分")
    private Integer payableAmount;

    @Schema(description = "推荐使用的用户券id，按核销顺序排列")
    private List<Long> userCouponIds;
    @Schema(description = "对应的优惠券模板id，与userCouponIds顺序一致")
    private List<Long> couponIds;
    @Schema(description = "每张券的使用明细，按核销顺序排列")
    private List<CouponUsageVO> usages;
}
