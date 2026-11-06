package com.tianji.promotion.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDateTime;

@Data
@Schema(description = "优惠券发放配置")
public class CouponIssueDTO {

    @Schema(description = "发放开始时间，省略表示立即发放；指定时必须是未来时间")
    private LocalDateTime issueBeginTime;

    @Schema(description = "发放结束时间，必须晚于开始时间和当前时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "发放结束时间不能为空")
    private LocalDateTime issueEndTime;

    @Schema(description = "领取后有效天数；0表示固定有效期，必须同时指定有效期起止时间")
    @NotNull(message = "有效期天数不能为空")
    @PositiveOrZero(message = "有效期天数不能为负数")
    private Integer termDays = 0;

    @Schema(description = "固定有效期开始时间，termDays为0时必填")
    private LocalDateTime termBeginTime;

    @Schema(description = "固定有效期结束时间，必须晚于有效期开始且不早于发放结束时间")
    private LocalDateTime termEndTime;
}
