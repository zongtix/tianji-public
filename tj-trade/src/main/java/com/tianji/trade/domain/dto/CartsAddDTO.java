package com.tianji.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "课程加入购物车")
public class CartsAddDTO {
    @Schema(description = "要加入购物车的课程id")
    @NotNull(message = "课程id不能为空")
    private Long courseId;
}