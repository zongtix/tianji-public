package com.tianji.promotion.mq;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.promotion.service.claim.CouponClaimProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.promotion.async", name = "enabled", havingValue = "true")
public class CouponClaimListener {
    private final CouponClaimProcessor processor;

    @RabbitListener(concurrency = "2", bindings = @QueueBinding(
            value = @Queue(value = "promotion.coupon.claim.queue", durable = "true"),
            exchange = @Exchange(value = "promotion.coupon.claim.exchange", type = "direct"),
            key = "claim"))
    public void onMessage(String requestId) {
        try {
            processor.process(requestId);
        } catch (BadRequestException failure) {
            recordFailure(requestId, true, failure.getMessage());
        } catch (RuntimeException failure) {
            log.warn("优惠券异步处理失败，requestId={}", requestId, failure);
            recordFailure(requestId, false, null);
        }
    }

    private void recordFailure(String requestId, boolean businessFailure, String message) {
        try {
            processor.recordFailure(requestId, businessFailure, message);
        } catch (RuntimeException failure) {
            log.error("优惠券失败状态暂无法保存，持久化请求将由发布任务重投，requestId={}", requestId, failure);
        }
    }
}
