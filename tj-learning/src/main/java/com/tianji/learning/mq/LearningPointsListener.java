package com.tianji.learning.mq;

import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.JsonUtils;
import org.springframework.amqp.core.Message;
import java.nio.charset.StandardCharsets;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mq.message.LearningPointsMessage;
import com.tianji.learning.mq.message.ReplyPointsMessage;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.points.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
@RequiredArgsConstructor
@Component
public class LearningPointsListener {
    private final IPointsRecordService pointsRecordService;

    /**
     * 课堂问答积分 —— 监听"写回答"消息
     *
     * 消息链路：
     *   用户在问答模块发布回答 → 生产者发送消息到 learning.topic 交换机
     *   → RoutingKey = "reply.new" → 本队列消费
     *
     * 绑定关系：
     *   队列：qa.points.queue（持久化）
     *   交换机：learning.topic（TOPIC 类型，支持通配符路由）
     *   RoutingKey：reply.new（精确匹配，只有"写回答"事件才会路由到此队列）
     *
     * @param message 回答消息，携带 replyId 作为幂等键，重复投递不会重复加分
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "qa.points.queue",durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.LEARNING_EXCHANGE,type= ExchangeTypes.TOPIC),
            key = MqConstants.Key.WRITE_REPLY
    ))
    public void listenWriteReplyMessage(ReplyPointsMessage message){
        pointsRecordService.addPointsRecord(
                message.getUserId(), 5, PointsRecordType.QA, String.valueOf(message.getReplyId()));
    }

    //签到
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "sign.points.queue",durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.LEARNING_EXCHANGE,type= ExchangeTypes.TOPIC),
            key = MqConstants.Key.SIGN_IN
    ))
    public void listenSignInMessage(SignInMessage message){
        // bizId = 签到日期，同一天只会加一次签到分
        pointsRecordService.addPointsRecord(
                message.getUserId(), message.getPoints(), PointsRecordType.SIGN, message.getBizId());
    }


    //学习课程
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "section.points.queue",durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.LEARNING_EXCHANGE,type= ExchangeTypes.TOPIC),
            key = MqConstants.Key.LEARN_SECTION
    ))
    public void listenLearningMessage(LearningPointsMessage message){
        // bizId = 小节id，同一个小节只会加一次学习分
        pointsRecordService.addPointsRecord(
                message.getUserId(), 10, PointsRecordType.LEARNING, String.valueOf(message.getSectionId()));
    }

    //记笔记
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "note.points.queue",durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.LEARNING_EXCHANGE,type= ExchangeTypes.TOPIC),
            key = MqConstants.Key.WRITE_NOTE
    ))
    public void ListenNoteMessage(Message message){
        addActionPoints(message, PointsRecordType.NOTE, 3, "noteId");
    }

    //写评论
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "comment.points.queue",durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.LEARNING_EXCHANGE,type= ExchangeTypes.TOPIC),
            key = MqConstants.Key.WRITE_COMMENT
    ))
    public void ListenCommentMessage(Message message){
        addActionPoints(message, PointsRecordType.COMMENT, 10, "commentId");
    }

    /** 新消息携带业务 ID；旧的数字 userId 消息必须携带生产端生成的稳定 messageId。 */
    private void addActionPoints(Message message, PointsRecordType type, int points, String idField) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8).trim();
        Long userId;
        String bizId;
        if (payload.startsWith("{")) {
            cn.hutool.json.JSONObject json = JsonUtils.parseObj(payload);
            userId = json.getLong("userId");
            Long id = json.getLong(idField);
            if (id == null || id <= 0) {
                throw new BadRequestException("积分消息缺少有效的 " + idField);
            }
            bizId = id.toString();
        } else {
            userId = Long.valueOf(payload);
            String messageId = message.getMessageProperties().getMessageId();
            if (messageId == null || messageId.isBlank() || messageId.length() > 61) {
                throw new BadRequestException("旧格式积分消息必须携带稳定的 messageId");
            }
            bizId = "mq:" + messageId;
        }
        pointsRecordService.addPointsRecord(userId, points, type, bizId);
    }

}
