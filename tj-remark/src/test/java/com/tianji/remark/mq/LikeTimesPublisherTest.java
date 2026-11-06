package com.tianji.remark.mq;

import com.tianji.remark.domain.dto.LikeChange;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import com.tianji.remark.exception.LikePublishRetryableException;
import com.tianji.remark.exception.LikePublishUnroutableException;

class LikeTimesPublisherTest {
    @Test
    void nackIsRetryable() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            data.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        LikePublishRetryableException error = assertThrows(LikePublishRetryableException.class,
                () -> new LikeTimesPublisher(rabbitTemplate).publish(List.of(change())));
        assertTrue(error.getMessage().contains("remark-like-NOTE-2-300"));
    }

    @Test
    void returnedMessageIsUnroutableAndNamesRoutingKey() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            String routingKey = invocation.getArgument(1);
            data.setReturned(new ReturnedMessage(new Message(new byte[0], new MessageProperties()),
                    312, "NO_ROUTE", "like.record.topic", routingKey));
            data.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        LikePublishUnroutableException error = assertThrows(LikePublishUnroutableException.class,
                () -> new LikeTimesPublisher(rabbitTemplate).publish(List.of(change())));
        assertTrue(error.getMessage().contains("NOTE.times.changed"));
        assertTrue(error.getMessage().contains("检查下游是否已声明队列"));
    }

    @Test
    void emptySnapshotsAreANoOpInTheRealPublisher() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new LikeTimesPublisher(rabbit).publish(java.util.Collections.emptyList()));
        org.mockito.Mockito.verifyNoInteractions(rabbit);
    }

    private LikeChange change() {
        LikeChange change = new LikeChange();
        change.setBizType("NOTE");
        change.setBizId(2L);
        change.setLikedTimes(1);
        change.setVersion(300L);
        return change;
    }
}
