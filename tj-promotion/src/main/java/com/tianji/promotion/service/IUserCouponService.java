package com.tianji.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.UserCouponPageVO;

/**
 * 用户优惠券服务
 */
public interface IUserCouponService extends IService<UserCoupon> {

    PageDTO<UserCouponPageVO> queryUserCouponPage(UserCouponQuery query);

    /** 为当前登录用户领取优惠券，校验发放窗口、库存和累计限领数量。 */
    void receiveCoupon(Long couponId);

    /** 为当前登录用户兑换优惠券，核销兑换码与发券在同一事务内完成。 */
    void exchangeCoupon(String code);

    Long receiveCouponForUser(Long couponId, Long userId);

    Long exchangeCouponForUser(String code, Long userId);
}
