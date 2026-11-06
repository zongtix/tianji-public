package com.tianji.learning.service.points;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.interaction.InteractionQuestionMapper;
import com.tianji.learning.mapper.interaction.InteractionReplyMapper;
import com.tianji.learning.mapper.points.PointsMessageMapper;
import com.tianji.learning.mapper.points.PointsRecordMapper;
import com.tianji.learning.mq.message.ReplyPointsMessage;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.mq.LearningPointsListener;
import com.tianji.learning.service.points.impl.PointsBoardServiceImpl;
import com.tianji.learning.service.points.impl.SignRecordServiceImpl;
import com.tianji.learning.task.PointsMessagePublishTask;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.ValueOperations;
import com.tianji.learning.service.interaction.IInteractionReplyService;
import com.tianji.learning.service.interaction.impl.InteractionReplyServiceImpl;
import com.tianji.learning.service.points.impl.PointsMessageServiceImpl;
import com.tianji.learning.service.points.impl.PointsRecordServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 使用隔离 H2 数据库与真实 Spring/MyBatis 事务，不依赖 Nacos、Redis 或 RabbitMQ。 */
@SpringJUnitConfig(PointsMessageTransactionTest.Config.class)
class PointsMessageTransactionTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private IPointsMessageService messages;
    @Autowired private PointsMessageMapper messageMapper;
    @Autowired private PointsRecordMapper recordMapper;
    @Autowired private IInteractionReplyService replies;

    @BeforeEach
    void prepareTables() {
        jdbc.execute("DROP TABLE IF EXISTS test_business");
        jdbc.execute("DROP TABLE IF EXISTS points_message");
        jdbc.execute("DROP TABLE IF EXISTS points_record");
        jdbc.execute("CREATE TABLE test_business (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE points_message (id BIGINT AUTO_INCREMENT PRIMARY KEY, message_key VARCHAR(128) NOT NULL UNIQUE, " +
                "routing_key VARCHAR(64) NOT NULL, payload TEXT NOT NULL, sent TINYINT NOT NULL DEFAULT 0, " +
                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE points_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, " +
                "type INT NOT NULL, points INT NOT NULL, biz_id VARCHAR(64), create_time DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "UNIQUE(user_id, type, biz_id))");
        UserContext.setUser(1L);
    }

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @Test
    void rolledBackBusinessDoesNotLeavePublishableMessage() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            jdbc.update("INSERT INTO test_business VALUES (1)");
            messages.saveMessage("qa:1", MqConstants.Key.WRITE_REPLY, ReplyPointsMessage.of(1L, 1L));
            throw new IllegalStateException("business rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_business", Integer.class));
        assertTrue(messageMapper.queryPendingMessages().isEmpty());
    }

    @Test
    void publisherCanOnlySeeMessageAfterCommit() {
        new TransactionTemplate(transactionManager).execute(status -> {
            messages.saveMessage("qa:1", MqConstants.Key.WRITE_REPLY, ReplyPointsMessage.of(1L, 1L));
            // 直接新建连接模拟另一个任务/实例，不复用当前事务绑定的连接。
            try (Connection connection = dataSource.getConnection();
                 ResultSet result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM points_message")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        assertEquals(1, messageMapper.queryPendingMessages().size());
        assertEquals(1, messageMapper.markSent(messageMapper.queryPendingMessages().get(0).getId()));
        assertTrue(messageMapper.queryPendingMessages().isEmpty());
    }

    @Test
    void signBusinessKeyRemainsUniqueAfterPublishing() {
        messages.saveMessage("sign:1:2026-10-03", MqConstants.Key.SIGN_IN, "test");
        messageMapper.markSent(messageMapper.queryPendingMessages().get(0).getId());
        assertThrows(DuplicateKeyException.class,
                () -> messages.saveMessage("sign:1:2026-10-03", MqConstants.Key.SIGN_IN, "test"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM points_message", Integer.class));
    }

    @Test
    void signRecoveryOnlyReadsThisUsersCurrentMonthIncludingSentMessages() {
        messages.saveMessage("sign:1:2026-10-02", MqConstants.Key.SIGN_IN, "test");
        messageMapper.markSent(messageMapper.queryPendingMessages().get(0).getId());
        messages.saveMessage("sign:1:2026-09-30", MqConstants.Key.SIGN_IN, "test");
        messages.saveMessage("sign:2:2026-10-02", MqConstants.Key.SIGN_IN, "test");
        assertEquals(java.util.List.of(java.time.LocalDate.of(2026, 10, 2)),
                messages.querySignDates(1L, java.time.LocalDate.of(2026, 10, 3)));
    }

    private ReplyDTO reply() {
        ReplyDTO reply = new ReplyDTO();
        reply.setQuestionId(42L);
        reply.setContent("回答正文");
        return reply;
    }

    @Test
    void answerAndPointsEventCommitTogetherThroughActualProducer() {
        replies.saveReply(reply());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM test_business", Integer.class));
        assertEquals("qa:1001", messageMapper.queryPendingMessages().get(0).getMessageKey());
    }

    @Test
    void messageInsertFailureRollsBackAnswerThroughActualProducer() {
        messages.saveMessage("qa:1001", MqConstants.Key.WRITE_REPLY, ReplyPointsMessage.of(1L, 1001L));
        assertThrows(DuplicateKeyException.class, () -> replies.saveReply(reply()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_business", Integer.class));
        assertEquals(1, messageMapper.queryPendingMessages().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void consumerCapsPointsAndDoesNotCreditRedeliveryTwice() {
        IPointsBoardService boards = mock(IPointsBoardService.class);
        PointsRecordServiceImpl service = new PointsRecordServiceImpl(messages, boards);
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        service.addPointsRecord(1L, 5, PointsRecordType.QA, "first");
        service.addPointsRecord(1L, 5, PointsRecordType.QA, "first");
        service.addPointsRecord(1L, 18, PointsRecordType.QA, "second");
        service.addPointsRecord(1L, 5, PointsRecordType.QA, "third");
        assertEquals(20, jdbc.queryForObject("SELECT SUM(points) FROM points_record", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM points_record", Integer.class));
        assertEquals(2, messageMapper.queryPendingMessages().size());
        String month = java.time.LocalDate.now().format(com.tianji.common.utils.DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        service.syncPointsBoard(1L, month);
        service.syncPointsBoard(1L, month);
        verify(boards, times(2)).updateBoardScore(1L, month, 20);
    }

    @Test
    void invalidPointsParametersDoNotWriteRecordsOrUpdateBoard() {
        IPointsBoardService boards = mock(IPointsBoardService.class);
        PointsRecordServiceImpl service = new PointsRecordServiceImpl(messages, boards);
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        assertThrows(BadRequestException.class, () -> service.addPointsRecord(null, 5, PointsRecordType.QA));
        assertThrows(BadRequestException.class, () -> service.addPointsRecord(1L, 5, null));
        assertThrows(BadRequestException.class, () -> service.addPointsRecord(1L, 0, PointsRecordType.QA));
        assertThrows(BadRequestException.class, () -> service.addPointsRecord(1L, -5, PointsRecordType.QA));
        assertThrows(BadRequestException.class, () -> service.addPointsRecord(null, 5, PointsRecordType.QA, "invalid"));
        assertThrows(BadRequestException.class, () -> service.addPointsRecord(1L, 5, null, "invalid"));
        assertThrows(BadRequestException.class, () -> service.addPointsRecord(1L, 0, PointsRecordType.QA, "invalid"));
        assertThrows(BadRequestException.class, () -> service.addPointsRecord(1L, -5, PointsRecordType.QA, "invalid"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM points_record", Integer.class));
        verifyNoInteractions(boards);
    }

    @Test
    void failureSavingBoardTaskRollsBackThePointsRecordToo() {
        jdbc.execute("ALTER TABLE points_message ADD CONSTRAINT reject_board_task CHECK (routing_key <> 'points.board.sync')");
        PointsRecord record = new PointsRecord().setUserId(1L).setType(PointsRecordType.SIGN)
                .setPoints(1).setBizId("rollback").setCreateTime(LocalDateTime.now());
        assertThrows(RuntimeException.class, () -> messages.savePointsRecord(record));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM points_record", Integer.class));
        assertTrue(messageMapper.queryPendingMessages().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisFailureIsRetriedEvenAfterRedeliveryHitsTheDailyLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> ops = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(ops);
        when(ops.add(anyString(), eq("1"), eq(20D)))
                .thenThrow(new IllegalStateException("Redis down")).thenReturn(true);
        PointsBoardServiceImpl boards = new PointsBoardServiceImpl(redis, null);
        PointsRecordServiceImpl records = new PointsRecordServiceImpl(messages, boards);
        ReflectionTestUtils.setField(records, "baseMapper", recordMapper);
        records.addPointsRecord(1L, 20, PointsRecordType.QA, "full-limit");
        PointsMessagePublishTask publisher = new PointsMessagePublishTask(messageMapper, mock(RabbitTemplate.class), redis, records);
        publisher.publishPendingMessages();
        assertEquals(1, messageMapper.queryPendingMessages().size());
        records.addPointsRecord(1L, 20, PointsRecordType.QA, "full-limit");
        publisher.publishPendingMessages();
        assertTrue(messageMapper.queryPendingMessages().isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM points_record", Integer.class));
        verify(ops, times(2)).add(anyString(), eq("1"), eq(20D));
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedCompletionMarkerReplaysTheSameTotalWithoutDoubleCrediting() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> ops = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(ops);
        PointsRecordServiceImpl records = new PointsRecordServiceImpl(messages, new PointsBoardServiceImpl(redis, null));
        ReflectionTestUtils.setField(records, "baseMapper", recordMapper);
        records.addPointsRecord(1L, 5, PointsRecordType.QA, "once");
        PointsMessageMapper failingMarker = mock(PointsMessageMapper.class);
        when(failingMarker.queryPendingMessages()).thenAnswer(invocation -> messageMapper.queryPendingMessages());
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(failingMarker.markSent(anyLong())).thenAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("marker down");
            return messageMapper.markSent(invocation.getArgument(0));
        });
        PointsMessagePublishTask publisher = new PointsMessagePublishTask(failingMarker, mock(RabbitTemplate.class), redis, records);
        publisher.publishPendingMessages();
        publisher.publishPendingMessages();
        verify(ops, times(2)).add(anyString(), eq("1"), eq(5D));
        verify(ops, never()).incrementScore(anyString(), anyString(), anyDouble());
        assertTrue(messageMapper.queryPendingMessages().isEmpty());
    }

    @Test
    void pendingSyncUsesItsOriginalMonthAndCombinesAllPointsTypes() {
        LocalDateTime oldTime = LocalDate.now().minusMonths(1).withDayOfMonth(1).atTime(12, 0);
        messages.savePointsRecord(new PointsRecord().setUserId(1L).setType(PointsRecordType.QA)
                .setPoints(5).setBizId("old-qa").setCreateTime(oldTime));
        messages.savePointsRecord(new PointsRecord().setUserId(1L).setType(PointsRecordType.SIGN)
                .setPoints(1).setBizId("old-sign").setCreateTime(oldTime));
        messages.savePointsRecord(new PointsRecord().setUserId(1L).setType(PointsRecordType.SIGN)
                .setPoints(1).setBizId("new-sign").setCreateTime(LocalDateTime.now()));
        IPointsBoardService boards = mock(IPointsBoardService.class);
        PointsRecordServiceImpl records = new PointsRecordServiceImpl(messages, boards);
        ReflectionTestUtils.setField(records, "baseMapper", recordMapper);
        new PointsMessagePublishTask(messageMapper, mock(RabbitTemplate.class), mock(StringRedisTemplate.class), records)
                .publishPendingMessages();
        String oldMonth = oldTime.format(com.tianji.common.utils.DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        verify(boards, times(2)).updateBoardScore(1L, oldMonth, 6);
        assertEquals(0L, messageMapper.countPendingBoardSync(oldMonth));
        assertTrue(messageMapper.queryPendingMessages().isEmpty());
    }

    @Test
    void noteAndCommentRedeliveriesAreIdempotentForBothMessageFormats() {
        PointsRecordServiceImpl records = new PointsRecordServiceImpl(messages, mock(IPointsBoardService.class));
        ReflectionTestUtils.setField(records, "baseMapper", recordMapper);
        LearningPointsListener listener = new LearningPointsListener(records);
        Message note = new Message("{\"userId\":1,\"noteId\":77}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        listener.ListenNoteMessage(note);
        listener.ListenNoteMessage(note);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("stable-comment-88");
        Message comment = new Message("1".getBytes(StandardCharsets.UTF_8), properties);
        listener.ListenCommentMessage(comment);
        listener.ListenCommentMessage(comment);
        assertEquals(13, jdbc.queryForObject("SELECT SUM(points) FROM points_record", Integer.class));
        assertEquals(2, messageMapper.queryPendingMessages().size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void readingCalendarRestoresSentSignRecordsAfterBitmapLoss() {
        LocalDate today = LocalDate.now();
        messages.saveMessage("sign:1:" + today, MqConstants.Key.SIGN_IN, "test");
        messageMapper.markSent(messageMapper.queryPendingMessages().get(0).getId());
        assertTrue(messageMapper.queryPendingMessages().isEmpty());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        Set<Long> offsets = new HashSet<>();
        when(ops.setBit(anyString(), anyLong(), eq(true))).thenAnswer(invocation -> offsets.add(invocation.getArgument(1)));
        when(ops.bitField(anyString(), any())).thenAnswer(invocation -> {
            long bitmap = 0;
            for (long offset : offsets) bitmap |= 1L << (today.getDayOfMonth() - offset - 1);
            return List.of(bitmap);
        });
        SignRecordVO result = new SignRecordServiceImpl(redis, messages).querySignRecords();
        assertEquals(1, result.getSignDays());
        assertEquals((byte) 1, result.getSignRecords().get(today.getDayOfMonth() - 1));
        assertEquals(today.getDayOfMonth(), result.getSignRecords().size());
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.tianji.learning.mapper.points")
    @Import(PointsMessageServiceImpl.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:points-messages;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDefaultEnumTypeHandler(MybatisEnumTypeHandler.class);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        InteractionReplyMapper replyMapper(JdbcTemplate jdbc) {
            InteractionReplyMapper replyMapper = mock(InteractionReplyMapper.class);
            when(replyMapper.insert(any(InteractionReply.class))).thenAnswer(invocation -> {
                InteractionReply reply = invocation.getArgument(0);
                reply.setId(1001L);
                jdbc.update("INSERT INTO test_business VALUES (1001)");
                return 1;
            });
            return replyMapper;
        }

        @Bean
        IInteractionReplyService replies(IPointsMessageService messages, InteractionReplyMapper replyMapper) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InteractionQuestion.class);
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InteractionReply.class);
            InteractionReplyServiceImpl service = new InteractionReplyServiceImpl(
                    mock(InteractionQuestionMapper.class), null, null, messages,
                    mock(com.tianji.learning.service.interaction.BotReplyService.class));
            ReflectionTestUtils.setField(service, "baseMapper", replyMapper);
            return service;
        }
    }
}
