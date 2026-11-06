package com.tianji.promotion.domain.vo;

import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.UserCouponStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "本人优惠券分页数据")
public class UserCouponPageVO {
    @Schema(description = "用户券ID，不是优惠券模板ID")
    private Long id;
    @Schema(description = "优惠券模板ID")
    private Long couponId;
    @Schema(description = "优惠券名称")
    private String name;
    @Schema(description = "是否限定使用范围")
    private Boolean specific;
    @Schema(description = "折扣类型")
    private DiscountType discountType;
    @Schema(description = "折扣门槛，单位分")
    private Integer thresholdAmount;
    @Schema(description = "折扣值，金额为分，折扣80表示8折")
    private Integer discountValue;
    @Schema(description = "最高优惠金额，单位分")
    private Integer maxDiscountAmount;
    @Schema(description = "用户券实际有效期开始时间")
    private LocalDateTime termBeginTime;
    @Schema(description = "用户券实际有效期结束时间")
    private LocalDateTime termEndTime;
    @Schema(description = "状态：1未使用，2已使用，3已失效")
    private UserCouponStatus status;
    @Schema(description = "使用时间")
    private LocalDateTime usedTime;
    @Schema(description = "领取时间")
    private LocalDateTime createTime;
}
