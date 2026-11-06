package com.tianji.trade.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderProgressNodeVO {
    @Schema(description = "订单进度节点名称")
    private String name;
    @Schema(description = "订单进度节点名称对应的时间")
    private LocalDateTime time;
}