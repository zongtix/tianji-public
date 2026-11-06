package com.tianji.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.validate.annotations.EnumValid;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "退款审批模型")
public class ApproveFormDTO{
    @Schema(description = "退款id")
    @NotNull(message = "退款id不能为空")
    private Long id;
    @NotNull(message = "审批类型不能为空")
    @EnumValid(enumeration = {1,2}, message = "审批只有同意和拒绝两种操作")
    @Schema(description = "审批类型，1：同意，2：拒绝")
    public Integer approveType;
    @Schema(description = "审批意见")
    private String approveOpinion;
    @Schema(description = "备注")
    private String remark;
}