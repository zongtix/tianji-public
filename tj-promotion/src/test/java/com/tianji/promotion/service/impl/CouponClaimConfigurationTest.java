package com.tianji.promotion.service.impl;

import com.tianji.promotion.controller.CouponClaimController;
import com.tianji.promotion.mapper.CouponClaimRequestMapper;
import com.tianji.promotion.mq.CouponClaimListener;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.service.claim.CouponClaimProcessor;
import com.tianji.promotion.service.claim.CouponClaimService;
import com.tianji.promotion.task.CouponClaimPublishTask;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CouponClaimConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CouponClaimRequestMapper.class, () -> mock(CouponClaimRequestMapper.class))
            .withBean(IUserCouponService.class, () -> mock(IUserCouponService.class))
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .withUserConfiguration(CouponClaimService.class, CouponClaimProcessor.class,
                    CouponClaimController.class, CouponClaimListener.class, CouponClaimPublishTask.class);

    @Test
    void asynchronousComponentsAreDisabledUnlessExplicitlyEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CouponClaimService.class);
            assertThat(context).doesNotHaveBean(CouponClaimController.class);
            assertThat(context).doesNotHaveBean(CouponClaimListener.class);
            assertThat(context).doesNotHaveBean(CouponClaimPublishTask.class);
        });
    }

    @Test
    void explicitOptInLoadsAsynchronousComponents() {
        runner.withPropertyValues("tj.promotion.async.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CouponClaimService.class);
            assertThat(context).hasSingleBean(CouponClaimProcessor.class);
            assertThat(context).hasSingleBean(CouponClaimController.class);
            assertThat(context).hasSingleBean(CouponClaimListener.class);
            assertThat(context).hasSingleBean(CouponClaimPublishTask.class);
        });
    }
}
