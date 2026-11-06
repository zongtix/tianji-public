package com.tianji.learning.task;

import com.tianji.common.constants.MqConstants;
import com.tianji.learning.domain.po.PointsMessage;
import com.tianji.learning.mapper.points.PointsMessageMapper;
import com.tianji.learning.service.points.IPointsRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PointsMessagePublishTaskTest {
    private PointsMessage message(String routingKey, String payload) {
        PointsMessage message = new PointsMessage();
        message.setId(42L);
        message.setMessageKey("test:42");
        message.setRoutingKey(routingKey);
        message.setPayload(payload);
        return message;
    }

    @Test
    void failedSendRemainsPendingAndNextRunCanPublish() {
        PointsMessageMapper mapper = mock(PointsMessageMapper.class);
        when(mapper.queryPendingMessages()).thenReturn(List.of(message(MqConstants.Key.WRITE_REPLY, "{\"userId\":1,\"replyId\":2}")));
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doThrow(new IllegalStateException("broker down")).doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        PointsMessagePublishTask task = new PointsMessagePublishTask(mapper, rabbit, mock(StringRedisTemplate.class), mock(IPointsRecordService.class));
        task.publishPendingMessages();
        verify(mapper, never()).markSent(anyLong());
        task.publishPendingMessages();
        verify(mapper).markSent(42L);
    }

    @Test
    void brokerNackOrUnroutableMessageRemainsPending() {
        for (boolean ack : new boolean[]{false, true}) {
            PointsMessageMapper mapper = mock(PointsMessageMapper.class);
            when(mapper.queryPendingMessages()).thenReturn(List.of(message(MqConstants.Key.WRITE_REPLY, "{}")));
            RabbitTemplate rabbit = mock(RabbitTemplate.class);
            doAnswer(invocation -> {
                CorrelationData correlation = invocation.getArgument(3);
                if (ack) {
                    correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", "learning.topic", "reply.new"));
                }
                correlation.getFuture().complete(new CorrelationData.Confirm(ack, "test"));
                return null;
            }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
            new PointsMessagePublishTask(mapper, rabbit, mock(StringRedisTemplate.class), mock(IPointsRecordService.class)).publishPendingMessages();
            verify(mapper, never()).markSent(anyLong());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void pendingSignRestoresOriginalMonthsBitmapBeforePublishing() {
        PointsMessageMapper mapper = mock(PointsMessageMapper.class);
        when(mapper.queryPendingMessages()).thenReturn(List.of(message(MqConstants.Key.SIGN_IN,
                "{\"userId\":1001,\"points\":1,\"bizId\":\"2026-09-30\"}")));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            ((CorrelationData) invocation.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        new PointsMessagePublishTask(mapper, rabbit, redis, mock(IPointsRecordService.class)).publishPendingMessages();
        org.mockito.InOrder order = inOrder(ops, rabbit, mapper);
        order.verify(ops).setBit("sign:uid:1001:202609", 29, true);
        order.verify(rabbit).send(eq(MqConstants.Exchange.LEARNING_EXCHANGE), eq(MqConstants.Key.SIGN_IN), any(Message.class), any(CorrelationData.class));
        order.verify(mapper).markSent(42L);
    }

    @Test
    void failedSentMarkerCausesSafeRedeliveryWithSamePayload() {
        PointsMessageMapper mapper = mock(PointsMessageMapper.class);
        when(mapper.queryPendingMessages()).thenReturn(List.of(message(MqConstants.Key.LEARN_SECTION,
                "{\"userId\":1,\"lessonId\":2,\"sectionId\":3}")));
        when(mapper.markSent(42L)).thenThrow(new IllegalStateException("database down")).thenReturn(1);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            ((CorrelationData) invocation.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        PointsMessagePublishTask task = new PointsMessagePublishTask(mapper, rabbit, mock(StringRedisTemplate.class), mock(IPointsRecordService.class));
        task.publishPendingMessages();
        task.publishPendingMessages();
        verify(rabbit, times(2)).send(anyString(), eq(MqConstants.Key.LEARN_SECTION), any(Message.class), any(CorrelationData.class));
        verify(mapper, times(2)).markSent(42L);
    }
}
