package com.tianji.remark.mq;

import com.tianji.api.dto.LikedTimesDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.StringUtils;
import com.tianji.remark.domain.dto.LikeChange;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import com.tianji.remark.exception.LikePublishRetryableException;
import com.tianji.remark.exception.LikePublishUnroutableException;

/**
 * 跨模块计数通知出口，把内部 LikeChange 快照转换为 tj-api 的 LikedTimesDTO。
 * 消息只含 bizType、bizId、likedTimes、version，不携带个人点赞关系；下游无需了解 Redis Key 或 remark 表结构。
 * 按类型路由到点赞交换机，等待 Broker confirm 并检查 returned，任一消息失败则任务保留整批 Stream 重试。
 * Broker 接收并成功路由不代表 learning 已更新数据库；下游通过版本条件更新处理重复/乱序。
 */
@Service
@RequiredArgsConstructor
public class LikeTimesPublisher {
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送本批每个对象的绝对数量快照，先发送全部，再逐个检查关联确认结果。
     * 失败可能发生在部分消息已发送之后，因此不能假设一批消息要么全发要么全不发。
     */
    public void publish(List<LikeChange> snapshots) {
        List<CorrelationData> pendingConfirms = new ArrayList<>(snapshots.size());
        for (LikeChange snapshot : snapshots) {
            // 内部 DTO → 公共消息 DTO：从关系变更提取对象计数和版本，减少服务间契约耦合。
            // 发送“当前总数”而非“+1/-1”，相同版本重投由消费者忽略，不会重复累计。
            LikedTimesDTO message = LikedTimesDTO.of(snapshot.getBizType(), snapshot.getBizId(),
                    snapshot.getLikedTimes(), snapshot.getVersion());
            String routingKey = StringUtils.format(
                    MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, snapshot.getBizType());
            // 类型+对象+版本用于关联这次发布与 Broker 确认；messageId 本身不会让 RabbitMQ 自动去重。
            // 真正的计数幂等由下游 liked_version < incomingVersion 的数据库条件更新保障。
            String messageId = "remark-like-" + snapshot.getBizType() + "-" +
                    snapshot.getBizId() + "-" + snapshot.getVersion();
            CorrelationData correlationData = new CorrelationData(messageId);

            rabbitTemplate.convertAndSend(MqConstants.Exchange.LIKE_RECORD_EXCHANGE, routingKey, message,
                    amqpMessage -> {
                        amqpMessage.getMessageProperties().setMessageId(messageId);
                        return amqpMessage;
                    }, correlationData);
            pendingConfirms.add(correlationData);
        }

        // 先发完一批，再等待确认；避免每条消息都同步等待一次 Broker 往返。
        for (CorrelationData correlationData : pendingConfirms) {
            // 只接受 Broker 确认且成功路由的消息；失败时不 ACK Stream，下轮重发同一版本。
            CorrelationData.Confirm confirm;
            try {
                confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new LikePublishRetryableException("等待 RabbitMQ 确认点赞消息超时：" + correlationData.getId(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LikePublishRetryableException("等待 RabbitMQ 确认点赞消息被中断：" + correlationData.getId(), e);
            } catch (ExecutionException e) {
                throw new LikePublishRetryableException("RabbitMQ 确认点赞消息失败：" + correlationData.getId(), e);
            }
            if (!confirm.isAck()) {
                throw new LikePublishRetryableException("RabbitMQ 未确认点赞消息：" + correlationData.getId());
            }
            if (correlationData.getReturned() != null) {
                throw new LikePublishUnroutableException("点赞消息无法路由，routingKey=" +
                        correlationData.getReturned().getRoutingKey() +
                        "，该 routing key 没有匹配队列，检查下游是否已声明队列；messageId=" + correlationData.getId());
            }
        }
    }
}
