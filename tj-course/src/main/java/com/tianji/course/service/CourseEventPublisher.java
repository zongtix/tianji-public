package com.tianji.course.service;

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 正式课程提交成功后才通知索引消费者，失败由定期巡检补偿。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CourseEventPublisher {
    private final RabbitMqHelper rabbit;

    public void publish(String key, Long courseId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(key, courseId);
                }
            });
        } else {
            send(key, courseId);
        }
    }

    private void send(String key, Long courseId) {
        try {
            rabbit.send(MqConstants.Exchange.COURSE_EXCHANGE, key, courseId);
        } catch (Exception failure) {
            log.error("课程已提交但变更通知发送失败，索引需巡检补偿 courseId={}, key={}", courseId, key, failure);
        }
    }
}
