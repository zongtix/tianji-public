package com.tianji.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueDTO;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.domain.po.Coupon;

import java.util.List;

/**
 * 优惠券规则服务
 */
public interface ICouponService extends IService<Coupon> {


    void saveCoupon(CouponFormDTO couponFormDTO);

    PageDTO<CouponPageVO> queryCouponPage(CouponQuery query);

    /** 查询发放中的公开优惠券，并计算当前用户能否领取、是否持有可使用券。 */
    List<CouponVO> queryIssuingCoupons();

    void issueCoupon(Long id, CouponIssueDTO dto);

    /** 根据发放时间推进状态，重复执行不会重置发放配置。 */
    void refreshCouponStatus();
}
