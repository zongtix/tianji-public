package com.tianji.promotion.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.constants.RegexConstants;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
@Schema(description = "优惠券兑换参数")
public class CouponExchangeDTO {

    @Schema(description = "兑换码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "兑换码不能为空")
    @Pattern(regexp = RegexConstants.COUPON_CODE_PATTERN, message = "无效兑换码")
    private String code;
}
