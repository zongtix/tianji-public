package com.tianji.promotion.service.impl;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.CodeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UserCouponExchangeTest {
    private AnnotationConfigApplicationContext context;
    private IUserCouponService service;
    private JdbcTemplate jdbc;
    private LocalDateTime now;
    private String code;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(UserCouponReceiveTest.TestConfig.class);
        service = context.getBean(IUserCouponService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        jdbc.execute("CREATE TABLE coupon (id BIGINT PRIMARY KEY, `name` VARCHAR(100), type INT, "
                + "discount_type INT, `specific` BOOLEAN, discount_value INT, threshold_amount INT, "
                + "max_discount_amount INT, obtain_way INT, issue_begin_time TIMESTAMP, issue_end_time TIMESTAMP, "
                + "term_days INT, term_begin_time TIMESTAMP, term_end_time TIMESTAMP, status INT, total_num INT, "
                + "issue_num INT, used_num INT, user_limit INT, ext_param VARCHAR(100), create_time TIMESTAMP, "
                + "update_time TIMESTAMP, creater BIGINT, updater BIGINT)");
        jdbc.execute("CREATE TABLE user_coupon (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT NOT NULL, coupon_id BIGINT NOT NULL, term_begin_time TIMESTAMP, "
                + "term_end_time TIMESTAMP NOT NULL, used_time TIMESTAMP, status INT NOT NULL DEFAULT 1, "
                + "create_time TIMESTAMP, update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE exchange_code (id INT PRIMARY KEY, code VARCHAR(10) UNIQUE, status INT, "
                + "user_id BIGINT, type INT, exchange_target_id BIGINT, create_time TIMESTAMP, "
                + "expired_time TIMESTAMP, update_time TIMESTAMP)");
        jdbc.update("INSERT INTO coupon (id,status,obtain_way,issue_begin_time,issue_end_time,"
                        + "term_days,total_num,issue_num,user_limit) VALUES (1,3,2,?,?,7,100,0,1)",
                now.minusDays(1), now.plusDays(2));
        code = addCode(1);
        UserContext.setUser(2L);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        context.close();
    }

    @Test
    void successfulExchangeSetsOwnerValidityAndIssuedCounter() {
        service.exchangeCoupon(code);
        UserCoupon record = service.list().get(0);
        assertEquals(2L, record.getUserId());
        assertEquals(1L, record.getCouponId());
        assertEquals(UserCouponStatus.UNUSED, record.getStatus());
        assertEquals(record.getTermBeginTime().plusDays(7), record.getTermEndTime());
        assertFalse(record.getTermBeginTime().isBefore(now));
        assertEquals(record.getTermBeginTime(), record.getCreateTime());
        assertEquals(1, issued());
        assertEquals(2, codeStatus(1));
        assertEquals(2L, jdbc.queryForObject("SELECT user_id FROM exchange_code WHERE id=1", Long.class));
    }

    @Test
    void fixedValidityCanStartInTheFuture() {
        jdbc.update("UPDATE coupon SET term_days=0,term_begin_time=?,term_end_time=? WHERE id=1",
                now.plusDays(1), now.plusDays(10));
        service.exchangeCoupon(code);
        UserCoupon record = service.list().get(0);
        assertEquals(now.plusDays(1), record.getTermBeginTime());
        assertEquals(now.plusDays(10), record.getTermEndTime());
    }

    @Test
    void differentCodesCanBeExchangedUpToConfiguredLimit() {
        String second = addCode(2);
        String third = addCode(3);
        jdbc.update("UPDATE coupon SET user_limit=2 WHERE id=1");
        service.exchangeCoupon(code);
        service.exchangeCoupon(second);
        assertThrows(BadRequestException.class, () -> service.exchangeCoupon(third));
        assertEquals(2, issued());
        assertEquals(2, service.count());
        assertEquals(1, codeStatus(3));
    }

    @Test
    void largestSupportedSerialCanBeExchanged() {
        service.exchangeCoupon(addCode(Integer.MAX_VALUE));
        assertEquals(2, codeStatus(Integer.MAX_VALUE));
        assertEquals(1, issued());
    }

    @Test
    void repeatedExchangeBySameOrDifferentUserCannotIssueAgain() {
        service.exchangeCoupon(code);
        assertThrows(BadRequestException.class, () -> service.exchangeCoupon(code));
        UserContext.setUser(129L);
        assertThrows(BadRequestException.class, () -> service.exchangeCoupon(code));
        assertEquals(1, issued());
        assertEquals(1, service.count());
        assertEquals(2L, jdbc.queryForObject("SELECT user_id FROM exchange_code WHERE id=1", Long.class));
    }

    @Test
    void loginIsRequired() {
        UserContext.removeUser();
        assertThrows(UnauthorizedException.class, () -> service.exchangeCoupon(code));
        assertUnusedAndNoReceipt(0);
    }

    @Test
    void malformedUnknownMismatchedAndOutOfRangeCodesAreRejected() {
        for (String input : new String[]{null, "", "IIIIIIIIII", CodeUtil.generateCode(100, 1),
                CodeUtil.generateCode(1, 2), CodeUtil.generateCode(0, 1),
                CodeUtil.generateCode(1L + Integer.MAX_VALUE, 1), CodeUtil.generateCode(0xFFFFFFFFL, 1)}) {
            assertThrows(BadRequestException.class, () -> service.exchangeCoupon(input));
        }
        assertUnusedAndNoReceipt(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=3", "expired_time=NULL", "expired_time=CURRENT_TIMESTAMP",
            "type=2", "exchange_target_id=999", "exchange_target_id=NULL", "code='ABCDEFGH'"})
    void invalidExchangeRecordsCannotIssue(String assignment) {
        jdbc.update("UPDATE exchange_code SET " + assignment + " WHERE id=1");
        assertThrows(BadRequestException.class, () -> service.exchangeCoupon(code));
        assertEquals(0, issued());
        assertEquals(0, service.count());
        assertNull(jdbc.queryForObject("SELECT user_id FROM exchange_code WHERE id=1", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"obtain_way=1", "status=1", "status=2", "status=4", "status=5",
            "issue_begin_time=DATEADD('DAY',1,CURRENT_TIMESTAMP)",
            "issue_end_time=CURRENT_TIMESTAMP", "issue_begin_time=NULL", "issue_end_time=NULL",
            "term_days=0,term_begin_time=DATEADD('DAY',-2,CURRENT_TIMESTAMP),term_end_time=CURRENT_TIMESTAMP"})
    void unavailableCouponsDoNotConsumeCode(String assignment) {
        jdbc.update("UPDATE coupon SET " + assignment + " WHERE id=1");
        assertThrows(BadRequestException.class, () -> service.exchangeCoupon(code));
        assertUnusedAndNoReceipt(0);
    }

    @Test
    void exhaustedStockDoesNotConsumeCode() {
        jdbc.update("UPDATE coupon SET issue_num=total_num WHERE id=1");
        assertThrows(BadRequestException.class, () -> service.exchangeCoupon(code));
        assertUnusedAndNoReceipt(100);
    }

    @Test
    void usedAndExpiredReceiptsCountTowardsQuota() {
        jdbc.update("UPDATE coupon SET user_limit=2,issue_num=2 WHERE id=1");
        for (int status : new int[]{2, 3}) {
            jdbc.update("INSERT INTO user_coupon (user_id,coupon_id,status,term_end_time) VALUES (2,1,?,?)",
                    status, now.plusDays(7));
        }
        assertThrows(BadRequestException.class, () -> service.exchangeCoupon(code));
        assertEquals(1, codeStatus(1));
        assertEquals(2, issued());
        assertEquals(2, service.count());
        UserContext.setUser(129L);
        service.exchangeCoupon(code);
        assertEquals(3, issued());
    }

    @Test
    void insertFailureRollsBackCodeOwnerStatusAndIssuedCounter() {
        jdbc.execute("ALTER TABLE user_coupon ADD CONSTRAINT fail_receipt CHECK (coupon_id<>1)");
        assertThrows(RuntimeException.class, () -> service.exchangeCoupon(code));
        assertUnusedAndNoReceipt(0);
        jdbc.execute("ALTER TABLE user_coupon DROP CONSTRAINT fail_receipt");
        service.exchangeCoupon(code);
        assertEquals(1, issued());
        assertEquals(2, codeStatus(1));
    }

    @Test
    void concurrentUsersCannotRedeemSameCodeTwice() throws Exception {
        assertEquals(1, concurrentExchanges(code, 2L, code, 129L));
        assertEquals(1, issued());
        assertEquals(1, service.count());
        assertEquals(2, codeStatus(1));
    }

    @Test
    void concurrentDifferentCodesCannotExceedSameUserQuota() throws Exception {
        String second = addCode(2);
        assertEquals(1, concurrentExchanges(code, 2L, second, 2L));
        assertEquals(1, issued());
        assertEquals(1, service.count());
        assertEquals(3, codeStatus(1) + codeStatus(2));
    }

    @Test
    void concurrentDifferentCodesCannotOverIssueLastCoupon() throws Exception {
        String second = addCode(2);
        jdbc.update("UPDATE coupon SET total_num=1 WHERE id=1");
        assertEquals(1, concurrentExchanges(code, 2L, second, 129L));
        assertEquals(1, issued());
        assertEquals(1, service.count());
        assertEquals(3, codeStatus(1) + codeStatus(2));
    }

    private int concurrentExchanges(String firstCode, long firstUser, String secondCode, long secondUser)
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = workers.submit(() -> exchange(firstCode, firstUser, ready, start));
            Future<Integer> second = workers.submit(() -> exchange(secondCode, secondUser, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private int exchange(String input, long userId, CountDownLatch ready, CountDownLatch start) throws Exception {
        UserContext.setUser(userId);
        ready.countDown();
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            service.exchangeCoupon(input);
            return 1;
        } catch (BadRequestException expected) {
            return 0;
        } finally {
            UserContext.removeUser();
        }
    }

    private String addCode(int id) {
        String value = CodeUtil.generateCode(id, 1);
        jdbc.update("INSERT INTO exchange_code (id,code,status,type,exchange_target_id,expired_time) "
                + "VALUES (?,?,1,1,1,?)", id, value, now.plusDays(2));
        return value;
    }

    private int issued() {
        return jdbc.queryForObject("SELECT issue_num FROM coupon WHERE id=1", Integer.class);
    }

    private int codeStatus(int id) {
        return jdbc.queryForObject("SELECT status FROM exchange_code WHERE id=?", Integer.class, id);
    }

    private void assertUnusedAndNoReceipt(int issued) {
        assertEquals(issued, issued());
        assertEquals(0, service.count());
        assertEquals(1, codeStatus(1));
        assertNull(jdbc.queryForObject("SELECT user_id FROM exchange_code WHERE id=1", Long.class));
    }
}
