package com.tianji.promotion.task;

import com.tianji.promotion.service.ICouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponIssueTask {
    private final ICouponService couponService;

    @Scheduled(fixedDelay = 60_000)
    public void refreshCouponStatus() {
        couponService.refreshCouponStatus();
    }
}
