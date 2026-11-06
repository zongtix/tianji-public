package com.tianji.course.service;

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class CourseEventPublisherTest {
    private final RabbitMqHelper rabbit = mock(RabbitMqHelper.class);
    private final CourseEventPublisher publisher = new CourseEventPublisher(rabbit);
    private final TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(
            new DriverManagerDataSource("jdbc:h2:mem:publisher", "sa", "")));

    @Test
    void publishesOnlyAfterCommit() {
        transaction.executeWithoutResult(status -> {
            publisher.publish(MqConstants.Key.COURSE_UP_KEY, 10L);
            verifyNoInteractions(rabbit);
        });
        verify(rabbit).send(MqConstants.Exchange.COURSE_EXCHANGE, MqConstants.Key.COURSE_UP_KEY, 10L);
    }

    @Test
    void rollbackPublishesNothing() {
        transaction.executeWithoutResult(status -> {
            publisher.publish(MqConstants.Key.COURSE_DOWN_KEY, 10L);
            status.setRollbackOnly();
        });
        verifyNoInteractions(rabbit);
    }

    @Test
    void brokerFailureDoesNotMisreportACommittedCourseAsFailed() {
        doThrow(new IllegalStateException("broker unavailable")).when(rabbit).send(anyString(), anyString(), any());
        assertThatCode(() -> transaction.executeWithoutResult(status ->
                publisher.publish(MqConstants.Key.COURSE_UP_KEY, 10L))).doesNotThrowAnyException();
        verify(rabbit).send(MqConstants.Exchange.COURSE_EXCHANGE, MqConstants.Key.COURSE_UP_KEY, 10L);
    }
}
