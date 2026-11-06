package com.tianji.learning.mq;

import com.tianji.api.dto.LikedTimesDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.learning.mapper.interaction.InteractionReplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * remark 点赞链路在 learning 的落点：消费 QA 数量快照并更新 interaction_reply 的冗余计数。
 * 不在这里增删个人关系，也不读取 remark 数据库；服务之间通过 tj-api 的 LikedTimesDTO 协作。
 * 四个并发消费者可能乱序执行，依赖 Mapper 的版本条件更新而非 MQ messageId 实现幂等。
 */
@RequiredArgsConstructor
@Slf4j
@Component
public class LikeTimesChangeListener {

    private final InteractionReplyMapper replyMapper;

    /**
     * 每次有效状态变化在 Stream 留快照，remark 批次按对象合并后再发布；单线程消费仍可能积压。
     * 更新 SQL 带 liked_version < #{version} 条件，并发/乱序/重复消息会被条件更新忽略，
     * 不会写错总数，因此可安全提高并发度。
     */
    @RabbitListener(concurrency = "4", bindings = @QueueBinding(
            value = @Queue(value = "learning.qa.liked.times.queue", durable = "true"),
            exchange = @Exchange(
                    name = MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
                    type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.QA_LIKED_TIMES_KEY
    ))
    public void listenQaLikedTimesChange(LikedTimesDTO message) {
        // 无法定位目标回答或点赞总数的消息属于无效数据，记录后结束，避免对业务表执行错误更新。
        if (message == null || !"QA".equals(message.getBizType()) || message.getBizId() == null
                || message.getLikedTimes() == null || message.getVersion() == null || message.getVersion() < 1) {
            log.error("收到无效的问答点赞数变更消息：{}", message);
            return;
        }

        // 消息携带总数快照和递增版本；SQL 条件更新会忽略重复消息及晚到的旧版本。
        int updated = replyMapper.updateLikedTimesIfNewer(
                message.getBizId(), message.getLikedTimes(), message.getVersion());
        if (updated > 0) {
            log.info("已同步回答{}的点赞总数：{}，版本：{}",
                    message.getBizId(), message.getLikedTimes(), message.getVersion());
        }
    }
}
