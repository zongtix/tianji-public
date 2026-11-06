package com.tianji.promotion.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueDTO;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.ICouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券规则接口
 */
@Tag(name = "优惠券相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/coupons")
public class CouponController {
    private final ICouponService couponService;

    @Operation(summary = "新增优惠券")
    @PostMapping
    public void saveCoupon(@RequestBody @Validated CouponFormDTO couponFormDTO) {
        couponService.saveCoupon(couponFormDTO);
    }

    @Operation(summary = "分页查询优惠券", description = "按名称、折扣类型和状态筛选，固定按创建时间、id倒序")
    @GetMapping("/page")
    public PageDTO<CouponPageVO> queryCouponPage(@Validated CouponQuery query) {
        return couponService.queryCouponPage(query);
    }

    @Operation(summary = "查询发放中的公开优惠券及当前用户领取状态")
    @GetMapping("/list")
    public List<CouponVO> queryIssuingCoupons() {
        return couponService.queryIssuingCoupons();
    }

    @Operation(summary = "发放优惠券", description = "仅支持草稿状态的优惠券，支持手动领取和兑换码两种方式；省略开始时间立即发放，否则定时发放")
    @PutMapping("/{id}/issue")
    public void issueCoupon(@PathVariable("id") Long id, @RequestBody @Validated CouponIssueDTO dto) {
        couponService.issueCoupon(id, dto);
    }
}
