package com.tianji.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;

/**
 * 兑换码服务
 */
public interface IExchangeCodeService extends IService<ExchangeCode> {
    /**
     * 在优惠券发放事务提交后，异步生成并保存该券的兑换码。
     */
    void asyncGenerateCode(Coupon coupon);
}
