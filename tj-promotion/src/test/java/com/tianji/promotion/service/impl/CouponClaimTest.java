package com.tianji.promotion.service.impl;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.CouponClaimRequest;
import com.tianji.promotion.domain.vo.CouponClaimResultVO;
import com.tianji.promotion.mapper.CouponClaimRequestMapper;
import com.tianji.promotion.mq.CouponClaimListener;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.service.claim.CouponClaimProcessor;
import com.tianji.promotion.service.claim.CouponClaimService;
import com.tianji.promotion.task.CouponClaimPublishTask;
import com.tianji.promotion.utils.CodeUtil;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CouponClaimTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private CouponClaimService service;
    private CouponClaimProcessor processor;
    private CouponClaimRequestMapper mapper;
    private CouponClaimListener listener;

    @BeforeEach
    void setUp() throws Exception {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        service = context.getBean(CouponClaimService.class);
        processor = context.getBean(CouponClaimProcessor.class);
        mapper = context.getBean(CouponClaimRequestMapper.class);
        listener = new CouponClaimListener(processor);
        jdbc.execute("CREATE TABLE coupon (id BIGINT PRIMARY KEY, name VARCHAR(100), type INT, "
                + "discount_type INT, specific BOOLEAN, discount_value INT, threshold_amount INT, "
                + "max_discount_amount INT, obtain_way INT, issue_begin_time TIMESTAMP, issue_end_time TIMESTAMP, "
                + "term_days INT, term_begin_time TIMESTAMP, term_end_time TIMESTAMP, status INT, total_num INT, "
                + "issue_num INT, used_num INT, user_limit INT, ext_param VARCHAR(100), create_time TIMESTAMP, "
                + "update_time TIMESTAMP, creater BIGINT, updater BIGINT)");
        jdbc.execute("CREATE TABLE user_coupon (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT NOT NULL, coupon_id BIGINT NOT NULL, term_begin_time TIMESTAMP, "
                + "term_end_time TIMESTAMP NOT NULL, used_time TIMESTAMP, status INT NOT NULL DEFAULT 1, "
                + "create_time TIMESTAMP, update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE exchange_code (id INT PRIMARY KEY, code VARCHAR(32), status INT, "
                + "user_id BIGINT, type INT, exchange_target_id BIGINT, create_time TIMESTAMP, "
                + "expired_time TIMESTAMP, update_time TIMESTAMP)");
        try (var stream = new ClassPathResource("db/coupon-claim-request.sql").getInputStream()) {
            jdbc.execute(StreamUtils.copyToString(stream, StandardCharsets.UTF_8)
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", ""));
        }
        coupon();
        UserContext.setUser(2L);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        context.close();
    }

    @Test
    void acceptedRequestIsPersistedWithoutIssuingOrDependingOnConsumerThreadLocal() {
        CouponClaimResultVO accepted = service.receive(1L, "request-001");
        assertEquals("PENDING", accepted.getStatus());
        assertEquals(0, count("user_coupon"));
        assertEquals(0, issued());
        CouponClaimService restarted = new CouponClaimService(mapper);
        assertEquals(accepted.getRequestId(), restarted.get(accepted.getRequestId()).getRequestId());
        UserContext.removeUser();
        listener.onMessage(accepted.getRequestId());
        assertEquals("SUCCEEDED", mapper.selectById(accepted.getRequestId()).getStatus());
        assertEquals(1, count("user_coupon"));
        assertEquals(2L, jdbc.queryForObject("SELECT user_id FROM user_coupon", Long.class));
    }

    @Test
    void sameKeyReplaysSameResultAndCannotChangePayload() {
        CouponClaimResultVO first = service.receive(1L, "request-001");
        assertEquals(first.getRequestId(), service.receive(1L, "request-001").getRequestId());
        assertThrows(BadRequestException.class, () -> service.receive(3L, "request-001"));
        listener.onMessage(first.getRequestId());
        CouponClaimResultVO replay = service.receive(1L, "request-001");
        assertEquals("SUCCEEDED", replay.getStatus());
        assertNotNull(replay.getUserCouponId());
        assertEquals(1, count("coupon_claim_request"));
    }

    @Test
    void requestLookupIsOwnerOnlyAndInputsAreValidated() {
        String requestId = service.receive(1L, "request-001").getRequestId();
        UserContext.setUser(3L);
        assertThrows(BadRequestException.class, () -> service.get(requestId));
        assertThrows(BadRequestException.class, () -> service.receive(1L, "short"));
        assertThrows(BadRequestException.class, () -> service.receive(0L, "request-002"));
        assertThrows(BadRequestException.class, () -> service.exchange("invalid", "request-002"));
        assertThrows(BadRequestException.class,
                () -> service.exchange(CodeUtil.generateCode(2147483648L, 1L), "request-002"));
        UserContext.removeUser();
        assertThrows(UnauthorizedException.class, () -> service.receive(1L, "request-002"));
        assertThrows(UnauthorizedException.class, () -> service.get(requestId));
    }

    @Test
    void duplicateDeliveryAndLateFailureCannotChangeSuccessfulRequest() {
        String requestId = service.receive(1L, "request-001").getRequestId();
        listener.onMessage(requestId);
        listener.onMessage(requestId);
        processor.recordFailure(requestId, true, "late error");
        assertEquals("SUCCEEDED", service.get(requestId).getStatus());
        assertEquals(1, count("user_coupon"));
        assertEquals(1, issued());
        listener.onMessage("missing-request");
    }

    @Test
    void businessFailureAndRedeliveryDoNotConsumeStock() {
        jdbc.update("UPDATE coupon SET total_num=0 WHERE id=1");
        String requestId = service.receive(1L, "request-001").getRequestId();
        listener.onMessage(requestId);
        listener.onMessage(requestId);
        assertEquals("FAILED", service.get(requestId).getStatus());
        assertEquals("优惠券已领完", service.get(requestId).getMessage());
        assertEquals(0, count("user_coupon"));
        assertEquals(0, issued());
    }

    @Test
    void databaseFailureRollsBackIssuanceThenRetriesAfterBackoff() {
        jdbc.execute("ALTER TABLE user_coupon ADD CONSTRAINT reject_user CHECK (user_id <> 2)");
        String requestId = service.receive(1L, "request-001").getRequestId();
        listener.onMessage(requestId);
        assertEquals(0, issued());
        assertEquals(0, count("user_coupon"));
        CouponClaimRequest pending = mapper.selectById(requestId);
        assertEquals("PENDING", pending.getStatus());
        assertEquals(1, pending.getAttempts());
        listener.onMessage(requestId);
        assertEquals(1, mapper.selectById(requestId).getAttempts());
        jdbc.execute("ALTER TABLE user_coupon DROP CONSTRAINT reject_user");
        retryNow(requestId);
        listener.onMessage(requestId);
        assertEquals("SUCCEEDED", service.get(requestId).getStatus());
        assertEquals(1, issued());
    }

    @Test
    void resultWriteFailureRollsBackUserCouponInSameTransaction() {
        jdbc.execute("ALTER TABLE coupon_claim_request ADD CONSTRAINT reject_success CHECK (status <> 'SUCCEEDED')");
        String requestId = service.receive(1L, "request-001").getRequestId();
        listener.onMessage(requestId);
        assertEquals(0, issued());
        assertEquals(0, count("user_coupon"));
        assertEquals("PENDING", service.get(requestId).getStatus());
        jdbc.execute("ALTER TABLE coupon_claim_request DROP CONSTRAINT reject_success");
        retryNow(requestId);
        listener.onMessage(requestId);
        assertEquals(1, count("user_coupon"));
    }

    @Test
    void repeatedTechnicalFailureEventuallyBecomesVisibleFailure() {
        String requestId = service.receive(1L, "request-001").getRequestId();
        for (int attempt = 0; attempt < 5; attempt++) {
            retryNow(requestId);
            processor.recordFailure(requestId, false, "must not expose SQL details");
        }
        assertEquals("FAILED", service.get(requestId).getStatus());
        assertFalse(service.get(requestId).getMessage().contains("SQL"));
        listener.onMessage(requestId);
        assertEquals(0, issued());
    }

    @Test
    void exchangeCodeAndIssuanceRollbackTogetherOnLimitFailure() {
        String code = exchangeCode();
        jdbc.update("UPDATE coupon SET user_limit=0 WHERE id=1");
        String requestId = service.exchange(code, "exchange-001").getRequestId();
        listener.onMessage(requestId);
        assertEquals("FAILED", service.get(requestId).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM exchange_code", Integer.class));
        assertNull(jdbc.queryForObject("SELECT user_id FROM exchange_code", Long.class));
        assertEquals(0, issued());
    }

    @Test
    void twoUsersCannotExchangeSameCodeTwice() throws Exception {
        String code = exchangeCode();
        String first = service.exchange(code, "exchange-001").getRequestId();
        UserContext.setUser(3L);
        String second = service.exchange(code, "exchange-002").getRequestId();
        parallel(() -> listener.onMessage(first), () -> listener.onMessage(second));
        assertEquals(1, count("user_coupon"));
        assertEquals(1, issued());
        List<String> states = jdbc.queryForList("SELECT status FROM coupon_claim_request", String.class);
        assertTrue(states.containsAll(List.of("SUCCEEDED", "FAILED")));
        assertEquals(2, jdbc.queryForObject("SELECT status FROM exchange_code", Integer.class));
    }

    @Test
    void concurrentRequestsForSameUserRespectLifetimeLimit() throws Exception {
        String first = service.receive(1L, "request-001").getRequestId();
        String second = service.receive(1L, "request-002").getRequestId();
        parallel(() -> listener.onMessage(first), () -> listener.onMessage(second));
        assertEquals(1, count("user_coupon"));
        assertEquals(1, issued());
    }

    @Test
    void concurrentLastStockAndDuplicateDeliveryRemainIdempotent() throws Exception {
        jdbc.update("UPDATE coupon SET total_num=1 WHERE id=1");
        String first = service.receive(1L, "request-001").getRequestId();
        UserContext.setUser(3L);
        String second = service.receive(1L, "request-002").getRequestId();
        parallel(() -> listener.onMessage(first), () -> listener.onMessage(second));
        assertEquals(1, count("user_coupon"));
        assertEquals(1, issued());
        parallel(() -> listener.onMessage(first), () -> listener.onMessage(first));
        assertEquals(1, count("user_coupon"));
    }

    @Test
    void concurrentSubmitWithSameKeyCreatesOnlyOneRequest() throws Exception {
        Runnable submit = () -> {
            UserContext.setUser(2L);
            try {
                service.receive(1L, "request-001");
            } finally {
                UserContext.removeUser();
            }
        };
        parallel(submit, submit);
        assertEquals(1, count("coupon_claim_request"));
    }

    @Test
    void publisherRecoversLostDeliveryAndSkipsFinishedRequests() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        CouponClaimPublishTask task = new CouponClaimPublishTask(mapper, rabbit);
        String requestId = service.receive(1L, "request-001").getRequestId();
        task.publishPending();
        task.publishPending();
        verify(rabbit, times(1)).convertAndSend(anyString(), anyString(), eq(requestId), any(MessagePostProcessor.class));
        retryNow(requestId);
        task.publishPending();
        verify(rabbit, times(2)).convertAndSend(anyString(), anyString(), eq(requestId), any(MessagePostProcessor.class));
        listener.onMessage(requestId);
        task.publishPending();
        verifyNoMoreInteractions(rabbit);
    }

    @Test
    void publishFailureLeavesDurableRequestAvailableForRetry() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doThrow(new IllegalStateException("broker unavailable")).when(rabbit)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
        String requestId = service.receive(1L, "request-001").getRequestId();
        new CouponClaimPublishTask(mapper, rabbit).publishPending();
        CouponClaimRequest request = mapper.selectById(requestId);
        assertEquals("PENDING", request.getStatus());
        assertEquals(0, request.getAttempts());
        assertFalse(request.getNextPublishTime().isAfter(LocalDateTime.now()));
        assertEquals(0, issued());
    }

    @Test
    void concurrentDuplicateMessagesDoNotIssueTwiceEvenWhenUserMayReceiveMany() throws Exception {
        jdbc.update("UPDATE coupon SET user_limit=10 WHERE id=1");
        String requestId = service.receive(1L, "request-001").getRequestId();
        parallel(() -> listener.onMessage(requestId), () -> listener.onMessage(requestId));
        assertEquals("SUCCEEDED", service.get(requestId).getStatus());
        assertEquals(1, issued());
        assertEquals(1, count("user_coupon"));
    }

    @Test
    void synchronousAndAsynchronousReceiptsShareTheSameLifetimeLimit() throws Exception {
        String requestId = service.receive(1L, "request-001").getRequestId();
        Runnable synchronous = () -> {
            try {
                context.getBean(IUserCouponService.class).receiveCouponForUser(1L, 2L);
            } catch (BadRequestException expected) {
                assertEquals("已达到该优惠券的领取上限", expected.getMessage());
            }
        };
        parallel(synchronous, () -> listener.onMessage(requestId));
        assertEquals(1, issued());
        assertEquals(1, count("user_coupon"));
    }

    @Test
    void queuedRequestsRecheckTheIssueWindowAtExecutionTime() {
        String requestId = service.receive(1L, "request-001").getRequestId();
        jdbc.update("UPDATE coupon SET issue_end_time=? WHERE id=1", LocalDateTime.now().minusSeconds(1));
        listener.onMessage(requestId);
        assertEquals("FAILED", service.get(requestId).getStatus());
        assertEquals(0, issued());
    }

    @Test
    void exchangeTechnicalFailureDoesNotBurnCodeAndRetryCanSucceed() {
        String code = exchangeCode();
        String requestId = service.exchange(code, "exchange-001").getRequestId();
        jdbc.execute("ALTER TABLE user_coupon ADD CONSTRAINT reject_exchange CHECK (user_id <> 2)");
        listener.onMessage(requestId);
        assertEquals("PENDING", service.get(requestId).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM exchange_code", Integer.class));
        assertEquals(0, issued());
        jdbc.execute("ALTER TABLE user_coupon DROP CONSTRAINT reject_exchange");
        retryNow(requestId);
        listener.onMessage(requestId);
        assertEquals("SUCCEEDED", service.get(requestId).getStatus());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM exchange_code", Integer.class));
        assertEquals(1, issued());
    }

    private void coupon() {
        jdbc.update("INSERT INTO coupon (id,status,obtain_way,issue_begin_time,issue_end_time,"
                        + "term_days,total_num,issue_num,user_limit) VALUES (1,3,1,?,?,7,100,0,1)",
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
    }

    private String exchangeCode() {
        jdbc.update("UPDATE coupon SET obtain_way=2 WHERE id=1");
        String code = CodeUtil.generateCode(1, 1);
        jdbc.update("INSERT INTO exchange_code(id,code,status,type,exchange_target_id,expired_time) VALUES (1,?,1,1,1,?)",
                code, LocalDateTime.now().plusDays(1));
        return code;
    }

    private void retryNow(String requestId) {
        jdbc.update("UPDATE coupon_claim_request SET next_process_time=?,next_publish_time=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1), LocalDateTime.now().minusSeconds(1), requestId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int issued() {
        return jdbc.queryForObject("SELECT issue_num FROM coupon WHERE id=1", Integer.class);
    }

    private void parallel(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> firstResult = executor.submit(() -> runAfter(start, first));
            Future<?> secondResult = executor.submit(() -> runAfter(start, second));
            start.countDown();
            firstResult.get(15, TimeUnit.SECONDS);
            secondResult.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void runAfter(CountDownLatch start, Runnable action) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            action.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Configuration
    @Import(UserCouponReceiveTest.TestConfig.class)
    static class TestConfig {
        @Bean
        CouponClaimRequestMapper claimRequestMapper(SqlSessionFactory factory, SqlSessionTemplate sessions) {
            factory.getConfiguration().addMapper(CouponClaimRequestMapper.class);
            return sessions.getMapper(CouponClaimRequestMapper.class);
        }

        @Bean
        CouponClaimService claimService(CouponClaimRequestMapper mapper) {
            return new CouponClaimService(mapper);
        }

        @Bean
        CouponClaimProcessor claimProcessor(CouponClaimRequestMapper mapper, IUserCouponService coupons) {
            return new CouponClaimProcessor(mapper, coupons);
        }
    }
}
