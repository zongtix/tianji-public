package com.tianji.learning.service.points;

import com.tianji.common.autoconfigure.redisson.annotations.Lock;
import com.tianji.common.autoconfigure.redisson.aspect.LockAspect;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.config.PointsMqConfig;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mq.LearningPointsListener;
import com.tianji.learning.mq.message.LearningPointsMessage;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.points.impl.PointsRecordServiceImpl;
import com.tianji.learning.service.points.impl.SignRecordServiceImpl;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PointsFlowRegressionTest {
    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @Test
    void eachPointsListenerHasItsOwnQueue() {
        Set<String> queues = new HashSet<>();
        int listeners = 0;
        for (Method method : LearningPointsListener.class.getDeclaredMethods()) {
            RabbitListener listener = method.getAnnotation(RabbitListener.class);
            if (listener != null) {
                listeners++;
                assertTrue(queues.add(listener.bindings()[0].value().name()), "多个消费者竞争同一队列");
            }
        }
        assertEquals(5, listeners);
    }

    @Test
    void oldQueueIsUnboundWithoutDeletingItsBacklog() throws Exception {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueProperties("learning.points.queue")).thenReturn(new Properties());
        new PointsMqConfig().removeLegacyPointsBindings(admin).run(null);
        ArgumentCaptor<Binding> bindings = ArgumentCaptor.forClass(Binding.class);
        verify(admin, times(3)).removeBinding(bindings.capture());
        Set<String> keys = new HashSet<>();
        bindings.getAllValues().forEach(binding -> {
            assertEquals("learning.points.queue", binding.getDestination());
            keys.add(binding.getRoutingKey());
        });
        assertEquals(Set.of(MqConstants.Key.LEARN_SECTION, MqConstants.Key.WRITE_NOTE, MqConstants.Key.WRITE_COMMENT), keys);
        verify(admin, never()).deleteQueue(anyString());
    }

    @Test
    void missingOldQueueOrUnavailableBrokerDoesNotFailStartup() throws Exception {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        new PointsMqConfig().removeLegacyPointsBindings(admin).run(null);
        verify(admin, never()).removeBinding(any());
        when(admin.getQueueProperties("learning.points.queue")).thenThrow(new AmqpConnectException(new java.net.ConnectException()));
        assertDoesNotThrow(() -> new PointsMqConfig().removeLegacyPointsBindings(admin).run(null));
    }

    @Test
    void learningListenerPreservesStableBusinessIdOnRedelivery() {
        IPointsRecordService points = mock(IPointsRecordService.class);
        LearningPointsListener listener = new LearningPointsListener(points);
        LearningPointsMessage message = LearningPointsMessage.of(1001L, 2001L, 3001L);
        listener.listenLearningMessage(message);
        listener.listenLearningMessage(message);
        verify(points, times(2)).addPointsRecord(1001L, 10, PointsRecordType.LEARNING, "3001");
    }

    @Test
    void noteAndCommentMessagesRequireAnIdAndKeepTheirBusinessKey() {
        IPointsRecordService points = mock(IPointsRecordService.class);
        LearningPointsListener listener = new LearningPointsListener(points);
        org.springframework.amqp.core.MessageProperties properties = new org.springframework.amqp.core.MessageProperties();
        listener.ListenCommentMessage(new org.springframework.amqp.core.Message(
                "{\"userId\":1,\"commentId\":88}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties));
        verify(points).addPointsRecord(1L, 10, PointsRecordType.COMMENT, "88");
        assertThrows(com.tianji.common.exceptions.BadRequestException.class, () -> listener.ListenNoteMessage(
                new org.springframework.amqp.core.Message("1".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties)));
        assertThrows(com.tianji.common.exceptions.BadRequestException.class, () -> listener.ListenCommentMessage(
                new org.springframework.amqp.core.Message("{\"userId\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties)));
        verifyNoMoreInteractions(points);
    }

    @Test
    void lockTimeoutThrowsForBothEntryPointsWithoutExecutingInsert() throws Throwable {
        for (Class<?>[] parameters : new Class<?>[][] {
                {Long.class, int.class, PointsRecordType.class},
                {Long.class, int.class, PointsRecordType.class, String.class}}) {
            Method method = PointsRecordServiceImpl.class.getMethod("addPointsRecord", parameters);
            RedissonClient redis = mock(RedissonClient.class);
            RLock lock = mock(RLock.class);
            when(redis.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            MethodSignature signature = mock(MethodSignature.class);
            when(pjp.getSignature()).thenReturn(signature);
            when(signature.getMethod()).thenReturn(method);
            when(signature.getName()).thenReturn(method.getName());
            when(pjp.getTarget()).thenReturn(new PointsRecordServiceImpl(mock(IPointsMessageService.class), mock(IPointsBoardService.class)));
            when(pjp.getArgs()).thenReturn(parameters.length == 3
                    ? new Object[]{1001L, 10, PointsRecordType.LEARNING}
                    : new Object[]{1001L, 10, PointsRecordType.LEARNING, "3001"});
            assertThrows(RuntimeException.class,
                    () -> new LockAspect(redis).handleLock(pjp, method.getAnnotation(Lock.class)));
            verify(pjp, never()).proceed();
        }
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> prepareRedis(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.getBit(anyString(), anyLong())).thenReturn(false);
        when(ops.bitField(anyString(), any())).thenReturn(List.of(0L));
        UserContext.setUser(1001L);
        return ops;
    }

    @Test
    void signMessageIsDurableBeforeBitmapAndUsesSignDateForIdempotence() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = prepareRedis(redis);
        IPointsMessageService messages = mock(IPointsMessageService.class);
        SignResultVO result = new SignRecordServiceImpl(redis, messages).addSignRecords();
        LocalDate today = LocalDate.now();
        ArgumentCaptor<SignInMessage> payload = ArgumentCaptor.forClass(SignInMessage.class);
        org.mockito.InOrder order = inOrder(messages, ops);
        order.verify(messages).saveMessage(eq("sign:1001:" + today), eq(MqConstants.Key.SIGN_IN), payload.capture());
        order.verify(ops).setBit(anyString(), eq((long) today.getDayOfMonth() - 1), eq(true));
        assertEquals(1, result.getSignDays());
        assertEquals(1, payload.getValue().getPoints());
        assertEquals(today.toString(), payload.getValue().getBizId());
    }

    @Test
    void failedDatabaseWriteDoesNotSetSignBit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = prepareRedis(redis);
        IPointsMessageService messages = mock(IPointsMessageService.class);
        doThrow(new IllegalStateException("database unavailable")).when(messages)
                .saveMessage(anyString(), anyString(), any());
        assertThrows(IllegalStateException.class,
                () -> new SignRecordServiceImpl(redis, messages).addSignRecords());
        verify(ops, never()).setBit(anyString(), anyLong(), anyBoolean());
    }

    @Test
    void failedBitmapWriteLeavesAcceptedSignMessageForRecovery() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = prepareRedis(redis);
        when(ops.setBit(anyString(), anyLong(), eq(true))).thenThrow(new IllegalStateException("redis unavailable"));
        IPointsMessageService messages = mock(IPointsMessageService.class);
        SignResultVO result = new SignRecordServiceImpl(redis, messages).addSignRecords();
        assertEquals(1, result.getSignDays());
        verify(messages).saveMessage(anyString(), eq(MqConstants.Key.SIGN_IN), any(SignInMessage.class));
    }

    @Test
    void duplicateDatabaseBusinessKeyRejectsConcurrentSign() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = prepareRedis(redis);
        IPointsMessageService messages = mock(IPointsMessageService.class);
        doThrow(new DuplicateKeyException("same sign date")).when(messages).saveMessage(anyString(), anyString(), any());
        assertThrows(BizIllegalException.class, () -> new SignRecordServiceImpl(redis, messages).addSignRecords());
        verify(ops, never()).setBit(anyString(), anyLong(), anyBoolean());
    }

    @Test
    void previouslyAcceptedSignBitsAreRestoredBeforeCountingStreak() {
        LocalDate today = LocalDate.now();
        org.junit.jupiter.api.Assumptions.assumeTrue(today.getDayOfMonth() > 1);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = prepareRedis(redis);
        IPointsMessageService messages = mock(IPointsMessageService.class);
        when(messages.querySignDates(1001L, today)).thenReturn(List.of(today.minusDays(1)));
        when(ops.bitField(anyString(), any())).thenReturn(List.of(1L));
        SignResultVO result = new SignRecordServiceImpl(redis, messages).addSignRecords();
        assertEquals(2, result.getSignDays());
        org.mockito.InOrder order = inOrder(ops);
        order.verify(ops).setBit(anyString(), eq((long) today.getDayOfMonth() - 2), eq(true));
        order.verify(ops).bitField(anyString(), any());
    }
}
