package com.tianji.promotion.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.promotion.domain.dto.CouponExchangeDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.UserCouponPageVO;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 用户优惠券接口
 */
@Tag(name = "用户优惠券管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/user-coupons")
public class UserCouponController {
    private final IUserCouponService userCouponService;

    @Operation(summary = "分页查询本人优惠券")
    @GetMapping("/page")
    public PageDTO<UserCouponPageVO> queryUserCouponPage(@Valid UserCouponQuery query) {
        return userCouponService.queryUserCouponPage(query);
    }

    @Operation(summary = "领取公开发放的优惠券")
    @PostMapping("/{id}/receive")
    public void receiveCoupon(@PathVariable("id") Long couponId) {
        userCouponService.receiveCoupon(couponId);
    }

    @Operation(summary = "通过兑换码兑换优惠券")
    @PostMapping("/exchange")
    public void exchangeCoupon(@Valid @RequestBody CouponExchangeDTO dto) {
        userCouponService.exchangeCoupon(dto.getCode());
    }
}
