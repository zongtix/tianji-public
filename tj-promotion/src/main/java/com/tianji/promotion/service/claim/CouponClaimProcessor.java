package com.tianji.promotion.service.claim;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.promotion.domain.po.CouponClaimRequest;
import com.tianji.promotion.mapper.CouponClaimRequestMapper;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.promotion.async", name = "enabled", havingValue = "true")
public class CouponClaimProcessor {
    private final CouponClaimRequestMapper requestMapper;
    private final IUserCouponService userCouponService;

    @Transactional
    public void process(String requestId) {
        CouponClaimRequest request = lockPending(requestId);
        if (request == null || request.getNextProcessTime().isAfter(LocalDateTime.now())) {
            return;
        }
        Long userCouponId;
        if ("RECEIVE".equals(request.getOperation())) {
            userCouponId = userCouponService.receiveCouponForUser(request.getCouponId(), request.getUserId());
        } else if ("EXCHANGE".equals(request.getOperation())) {
            userCouponId = userCouponService.exchangeCouponForUser(request.getExchangeCode(), request.getUserId());
        } else {
            throw new BadRequestException("无效领取请求类型");
        }
        request.setStatus("SUCCEEDED").setUserCouponId(userCouponId).setMessage("领取成功")
                .setUpdateTime(LocalDateTime.now());
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("领取结果更新失败");
        }
    }

    @Transactional
    public void recordFailure(String requestId, boolean businessFailure, String message) {
        CouponClaimRequest request = lockPending(requestId);
        LocalDateTime now = LocalDateTime.now();
        if (request == null || request.getNextProcessTime().isAfter(now)) {
            return;
        }
        int attempts = request.getAttempts() + 1;
        request.setAttempts(attempts).setUpdateTime(now);
        if (businessFailure || attempts >= 5) {
            request.setStatus("FAILED").setMessage(businessFailure ? message : "系统繁忙，请重新提交领取请求");
        } else {
            LocalDateTime retryAt = now.plusSeconds(1L << attempts);
            request.setNextProcessTime(retryAt).setNextPublishTime(retryAt).setMessage("系统繁忙，正在重试");
        }
        requestMapper.updateById(request);
    }

    private CouponClaimRequest lockPending(String requestId) {
        CouponClaimRequest request = requestMapper.selectOne(Wrappers.<CouponClaimRequest>lambdaQuery()
                .eq(CouponClaimRequest::getId, requestId).last("FOR UPDATE"));
        return request != null && "PENDING".equals(request.getStatus()) ? request : null;
    }
}
