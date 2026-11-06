package com.tianji.promotion.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.promotion.domain.po.CouponClaimRequest;
import com.tianji.promotion.mapper.CouponClaimRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.promotion.async", name = "enabled", havingValue = "true")
public class CouponClaimPublishTask {
    private final CouponClaimRequestMapper requestMapper;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${tj.promotion.async.publish-interval-ms:1000}")
    public void publishPending() {
        LocalDateTime now = LocalDateTime.now();
        List<CouponClaimRequest> requests = requestMapper.selectList(Wrappers.<CouponClaimRequest>lambdaQuery()
                .select(CouponClaimRequest::getId).eq(CouponClaimRequest::getStatus, "PENDING")
                .le(CouponClaimRequest::getNextPublishTime, now).le(CouponClaimRequest::getNextProcessTime, now)
                .orderByAsc(CouponClaimRequest::getNextPublishTime, CouponClaimRequest::getId).last("LIMIT 100"));
        for (CouponClaimRequest request : requests) {
            try {
                rabbitTemplate.convertAndSend("promotion.coupon.claim.exchange", "claim", request.getId(), message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                });
                requestMapper.update(null, Wrappers.<CouponClaimRequest>lambdaUpdate()
                        .eq(CouponClaimRequest::getId, request.getId()).eq(CouponClaimRequest::getStatus, "PENDING")
                        .le(CouponClaimRequest::getNextPublishTime, now)
                        .set(CouponClaimRequest::getNextPublishTime, now.plusSeconds(10)));
            } catch (RuntimeException failure) {
                log.warn("优惠券请求投递失败，将重试，requestId={}", request.getId(), failure);
                break;
            }
        }
    }
}
