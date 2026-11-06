package com.tianji.pay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.pay.service.IPayOrderService;
import com.tianji.pay.service.IRefundOrderService;
import com.tianji.pay.service.impl.NotifyServiceImpl;
import com.tianji.pay.third.CommonPayProperties;
import com.tianji.pay.third.wx.WxPayClient;
import com.tianji.pay.third.wx.config.WxConfiguration;
import com.wechat.pay.contrib.apache.httpclient.notification.NotificationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WxPayStartupTest {
    private final IPayOrderService orders = mock(IPayOrderService.class);
    private final IRefundOrderService refunds = mock(IRefundOrderService.class);
    private final RabbitMqHelper messages = mock(RabbitMqHelper.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WxConfiguration.class, WxPayClient.class, NotifyServiceImpl.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(CommonPayProperties.class, CommonPayProperties::new)
            .withBean(IPayOrderService.class, () -> orders)
            .withBean(IRefundOrderService.class, () -> refunds)
            .withBean(RabbitMqHelper.class, () -> messages)
            .withPropertyValues("tj.pay.wx.private-key=", "tj.pay.wx.mch-id=local-unconfigured");

    @Test void missingMerchantCredentialsAllowStartupButCannotSendPaymentRequests() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            WxPayClient client = context.getBean(WxPayClient.class);
            context.getBean(NotifyServiceImpl.class);
            assertThat(context.getBeanFactory().containsSingleton("certificatesManager")).isFalse();
            assertThat(context.getBeanFactory().containsSingleton("closeableHttpClient")).isFalse();
            // Invalid PEM must fail before an outbound HTTP request can be sent.
            assertThatThrownBy(() -> client.doGetJson("http://127.0.0.1:1/payment", false))
                    .isInstanceOf(BeansException.class);
            verifyNoInteractions(orders, refunds, messages);
        });
    }

    @Test void missingMerchantCredentialsCannotAcceptCallbackOrChangeOrders() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NotificationRequest request = mock(NotificationRequest.class);
            when(request.getBody()).thenReturn("{}");
            assertThatThrownBy(() -> context.getBean(NotifyServiceImpl.class).handleWxPayNotify(request))
                    .isInstanceOf(BadRequestException.class);
            verifyNoInteractions(orders, refunds, messages);
        });
    }
}
