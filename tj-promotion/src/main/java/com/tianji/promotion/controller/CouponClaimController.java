package com.tianji.promotion.controller;

import com.tianji.promotion.domain.dto.CouponExchangeDTO;
import com.tianji.promotion.domain.vo.CouponClaimResultVO;
import com.tianji.promotion.service.claim.CouponClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user-coupons")
@Tag(name = "优惠券异步领取")
@ConditionalOnProperty(prefix = "tj.promotion.async", name = "enabled", havingValue = "true")
public class CouponClaimController {
    private final CouponClaimService claimService;

    @PostMapping("/{id}/receive-async")
    @Operation(summary = "提交异步领券请求，受理不代表领取成功")
    public CouponClaimResultVO receive(@PathVariable("id") Long couponId,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return claimService.receive(couponId, key);
    }

    @PostMapping("/exchange-async")
    @Operation(summary = "提交异步兑换请求，受理不代表兑换成功")
    public CouponClaimResultVO exchange(@Valid @RequestBody CouponExchangeDTO dto,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return claimService.exchange(dto.getCode(), key);
    }

    @GetMapping("/requests/{requestId}")
    @Operation(summary = "查询本人异步领取或兑换结果")
    public CouponClaimResultVO result(@PathVariable("requestId") String requestId) {
        return claimService.get(requestId);
    }
}
