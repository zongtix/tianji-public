package com.tianji.remark.config;

import com.tianji.common.constants.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 点赞通知的 RabbitMQ 声明层，与 LikeTimesPublisher 使用同一 TopicExchange。
 * QA 队列由 learning 的 LikeTimesChangeListener 声明绑定；本类声明 NOTE 持久化兜底队列及路由绑定。
 * 兜底队列让 NOTE 消息有存放处，但没有在这里完成笔记计数更新；持久化队列也不等同于下游消费确认。
 * 本类只声明交换机/队列/绑定；confirm/returns 的客户端行为还依赖已有 RabbitMQ 配置。
 */
@Configuration
public class LikeRabbitConfig {

    @Bean
    public TopicExchange likeRecordExchange() {
        return new TopicExchange(MqConstants.Exchange.LIKE_RECORD_EXCHANGE, true, false);
    }

    /** 笔记消费者尚未接入时，先由持久化队列接收消息，避免 NOTE 消息无法路由而卡住 Stream。 */
    @Bean
    public Queue noteLikedTimesQueue() {
        return new Queue("remark.note.liked.times.queue", true);
    }

    @Bean
    public Binding noteLikedTimesBinding(Queue noteLikedTimesQueue, TopicExchange likeRecordExchange) {
        return BindingBuilder.bind(noteLikedTimesQueue).to(likeRecordExchange)
                .with(MqConstants.Key.NOTE_LIKED_TIMES_KEY);
    }
}
