package com.tianji.learning.mq;

import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.learning.service.lesson.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Slf4j
@Component
public class LessonChangeListener {

    private final ILearningLessonService learningLessonService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learning.lesson.pay.queue", durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.ORDER_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_PAY_KEY
    ))
    public void listenLessonPay(OrderBasicDTO order) {
        if (order == null || CollUtils.isEmpty(order.getCourseIds()) || order.getUserId() == null) {
            log.error("接收到的MQ消息有误，订单数据为空");
            return;
        }
        log.info("监听到用户{}的订单{},将{}添加到课表中",
                order.getUserId(), order.getOrderId(), order.getCourseIds());
        learningLessonService.addUserLessons(order.getUserId(), order.getCourseIds());
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learning.lesson.refund.queue", durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.ORDER_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_REFUND_KEY
    ))
    public void listenLessonRefund(OrderBasicDTO order) {
        if (order == null || CollUtils.isEmpty(order.getCourseIds()) || order.getUserId() == null) {
            log.error("接收到的退款MQ消息有误，订单数据为空");
            return;
        }
        log.info("监听到用户{}的退款订单{},将{}从课表中移除",
                order.getUserId(), order.getOrderId(), order.getCourseIds());
        learningLessonService.deleteUserLessons(order.getUserId(), order.getCourseIds());
    }
}
