package com.tianji.pay.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;


import com.tianji.pay.sdk.dto.RefundApplyDTO;
import com.tianji.pay.sdk.dto.RefundResultDTO;
import com.tianji.pay.service.IRefundOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 退款订单 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2022-08-26
 */
@RestController
@RequestMapping("/refund-orders")
@RequiredArgsConstructor
@Tag(name = "支付相关接口")
public class RefundOrderController {

    private final IRefundOrderService refundOrderService;

    @PostMapping
    @Operation(summary = "申请退款接口")
    public RefundResultDTO applyRefund(@RequestBody RefundApplyDTO refundApplyDTO) {
        return refundOrderService.applyRefund(refundApplyDTO);
    }

    @GetMapping("{bizRefundOrderId}/status")
    @Operation(summary = "查询退款结果")
    public RefundResultDTO queryRefundResult(
            @Parameter(description = "业务端退款的子订单id") @PathVariable("bizRefundOrderId") Long bizRefundOrderId) {
        return refundOrderService.queryRefundResult(bizRefundOrderId);
    }

/*    @GetMapping("{bizRefundOrderId}/detail")
    @Operation(summary = "查询退款详情")
    public RefundResultDTO queryRefundDetail(
            @Parameter(description = "业务端退款的子订单id") @PathVariable("bizRefundOrderId") Long bizRefundOrderId) {
        return refundOrderService.queryRefundDetail(bizRefundOrderId);
    }*/
}
