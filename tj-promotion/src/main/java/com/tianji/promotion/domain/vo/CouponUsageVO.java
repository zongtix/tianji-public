package com.tianji.promotion.domain.vo;

import com.tianji.promotion.enums.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 推荐方案中单张优惠券的使用明细。
 */
@Data
@Schema(description = "单张优惠券的使用明细")
public class CouponUsageVO {

    @Schema(description = "用户券id")
    private Long userCouponId;
    @Schema(description = "优惠券模板id")
    private Long couponId;
    @Schema(description = "优惠券名称")
    private String couponName;

    @Schema(description = "折扣类型，1：每满减，2：折扣，3：无门槛，4：满减")
    private DiscountType discountType;
    @Schema(description = "使用门槛，单位分，0表示无门槛")
    private Integer thresholdAmount;
    @Schema(description = "折扣值，满减为抵扣金额；折扣为百分比，80表示8折")
    private Integer discountValue;
    @Schema(description = "最高优惠金额，单位分，0表示不限制")
    private Integer maxDiscountAmount;
    @Schema(description = "是否限定作用范围")
    private Boolean specific;

    @Schema(description = "优惠券配置的作用范围业务id")
    private List<Long> scopeBizIds;
    @Schema(description = "本次实际参与抵扣的课程id")
    private List<Long> matchedCourseIds;
    @Schema(description = "按使用顺序计算时，该券可抵扣的金额，单位分")
    private Integer applicableAmount;
    @Schema(description = "该券实际抵扣金额，单位分")
    private Integer discountAmount;

    @Schema(description = "可解释的规则说明")
    private String ruleDescription;
}
