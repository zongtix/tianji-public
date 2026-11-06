package com.tianji.learning.task;

import com.tianji.common.autoconfigure.mq.BasicIdMessageProcessor;
import com.tianji.common.autoconfigure.redisson.annotations.Lock;
import com.tianji.common.autoconfigure.redisson.enums.LockStrategy;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.JsonUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsMessage;
import com.tianji.learning.mapper.points.PointsMessageMapper;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.mq.message.PointsBoardSyncMessage;
import com.tianji.learning.service.points.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PointsMessagePublishTask {
    private final PointsMessageMapper mapper;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final IPointsRecordService pointsRecordService;

    @Scheduled(fixedDelay = 1000)
    @Lock(name = "lock:points:message-publish", lockStrategy = LockStrategy.SKIP_FAST)
    public void publishPendingMessages() {
        for (PointsMessage pending : mapper.queryPendingMessages()) {
            try {
                if (PointsBoardSyncMessage.ROUTING_KEY.equals(pending.getRoutingKey())) {
                    PointsBoardSyncMessage sync = JsonUtils.toBean(pending.getPayload(), PointsBoardSyncMessage.class);
                    pointsRecordService.syncPointsBoard(sync.getUserId(), sync.getMonth());
                    mapper.markSent(pending.getId());
                    continue;
                }
                // 签到先提交消息记录再更新 Bitmap；进程中断或 Redis 失败时，在这里恢复签到位。
                if (MqConstants.Key.SIGN_IN.equals(pending.getRoutingKey())) {
                    restoreSignBit(pending);
                }
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(String.valueOf(pending.getId()));
                Message message = new Message(pending.getPayload().getBytes(StandardCharsets.UTF_8), properties);
                new BasicIdMessageProcessor().postProcessMessage(message);
                CorrelationData correlation = new CorrelationData(String.valueOf(pending.getId()));
                rabbitTemplate.send(MqConstants.Exchange.LEARNING_EXCHANGE, pending.getRoutingKey(), message, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                // Broker 确认且消息成功路由后才标记；发送后落库失败会重发，由消费端业务键去重。
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("积分消息未确认或未路由到队列");
                }
                mapper.markSent(pending.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("积分待办任务处理失败，保留待重试：id={}, key={}", pending.getId(), pending.getMessageKey(), e);
            }
        }
    }

    private void restoreSignBit(PointsMessage pending) {
        SignInMessage sign = JsonUtils.toBean(pending.getPayload(), SignInMessage.class);
        LocalDate date = LocalDate.parse(sign.getBizId());
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX + sign.getUserId()
                + date.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        redisTemplate.opsForValue().setBit(key, date.getDayOfMonth() - 1, true);
    }
}
