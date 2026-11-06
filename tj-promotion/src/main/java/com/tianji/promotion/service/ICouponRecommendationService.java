package com.tianji.promotion.service;

import com.tianji.promotion.domain.dto.CouponRecommendationDTO;
import com.tianji.promotion.domain.vo.CouponRecommendationVO;

/**
 * 优惠券推荐服务。
 */
public interface ICouponRecommendationService {

    /**
     * 根据当前登录用户真实持有且有效的用户券、课程价格和课程范围，计算确定性的优惠券使用方案。
     */
    CouponRecommendationVO recommend(CouponRecommendationDTO dto);
}
