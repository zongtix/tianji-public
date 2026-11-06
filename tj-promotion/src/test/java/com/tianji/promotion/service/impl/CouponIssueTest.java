package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.dto.CouponIssueDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.mapper.CouponMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CouponIssueTest {
    private SqlSessionFactory factory;
    private SqlSession session;
    private CouponServiceImpl service;
    private LocalDateTime now;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:coupon_issue_" + UUID.randomUUID() + ";MODE=MySQL");
        // 保留此连接直到测试结束，独立数据库避免并发用例污染其他测试。
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE coupon (id BIGINT PRIMARY KEY, `name` VARCHAR(100), type INT, "
                    + "discount_type INT, `specific` BOOLEAN, discount_value INT, threshold_amount INT, "
                    + "max_discount_amount INT, obtain_way INT, issue_begin_time TIMESTAMP, issue_end_time TIMESTAMP, "
                    + "term_days INT, term_begin_time TIMESTAMP, term_end_time TIMESTAMP, status INT, total_num INT, "
                    + "issue_num INT, used_num INT, user_limit INT, ext_param VARCHAR(100), create_time TIMESTAMP, "
                    + "update_time TIMESTAMP, creater BIGINT, updater BIGINT)");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeHandlerRegistry().register(DiscountType.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(CouponStatus.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(ObtainType.class, MybatisEnumTypeHandler.class);
        configuration.addMapper(CouponMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(connection);
        service = new TestCouponService(session.getMapper(CouponMapper.class));
        now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        service.save(draft(1L));
        session.commit();
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        session.close();
    }

    @Test
    void immediateIssuePreservesRulesCountersAndRecordsOperator() {
        UserContext.setUser(123L);
        service.issueCoupon(1L, relativeTerm());
        Coupon actual = service.getById(1L);
        assertEquals(CouponStatus.ISSUING, actual.getStatus());
        assertFalse(actual.getIssueBeginTime().isBefore(now));
        assertFalse(actual.getIssueBeginTime().isAfter(LocalDateTime.now()));
        assertEquals(now.plusDays(2), actual.getIssueEndTime());
        assertEquals(30, actual.getTermDays());
        assertEquals(123L, actual.getUpdater());
        assertEquals(80, actual.getDiscountValue());
        assertEquals(100, actual.getTotalNum());
        assertEquals(2, actual.getIssueNum());
        assertEquals(1, actual.getUsedNum());
        assertEquals(2, actual.getUserLimit());
    }

    @Test
    void scheduledIssueWaitsForStart() {
        CouponIssueDTO dto = relativeTerm();
        dto.setIssueBeginTime(now.plusDays(1));
        service.issueCoupon(1L, dto);
        assertEquals(CouponStatus.UN_ISSUE, service.getById(1L).getStatus());
        service.refreshCouponStatus();
        assertEquals(CouponStatus.UN_ISSUE, service.getById(1L).getStatus());
    }

    @Test
    void fixedTermCanEndAtIssueEnd() {
        CouponIssueDTO dto = fixedTerm();
        dto.setTermEndTime(dto.getIssueEndTime());
        service.issueCoupon(1L, dto);
        Coupon actual = service.getById(1L);
        assertEquals(0, actual.getTermDays());
        assertEquals(dto.getTermBeginTime(), actual.getTermBeginTime());
        assertEquals(dto.getTermEndTime(), actual.getTermEndTime());
        assertEquals(CouponStatus.ISSUING, actual.getStatus());
    }

    @Test
    void scheduledFixedTermPersistsBothPeriods() {
        CouponIssueDTO dto = fixedTerm();
        dto.setIssueBeginTime(now.plusDays(1));
        service.issueCoupon(1L, dto);
        Coupon actual = service.getById(1L);
        assertEquals(CouponStatus.UN_ISSUE, actual.getStatus());
        assertEquals(dto.getIssueBeginTime(), actual.getIssueBeginTime());
        assertEquals(dto.getTermEndTime(), actual.getTermEndTime());
    }

    @Test
    void relativeTermClearsOldFixedTermColumns() {
        service.lambdaUpdate().eq(Coupon::getId, 1L)
                .set(Coupon::getTermBeginTime, now.minusDays(10))
                .set(Coupon::getTermEndTime, now.minusDays(1)).update();
        service.issueCoupon(1L, relativeTerm());
        assertNull(service.getById(1L).getTermBeginTime());
        assertNull(service.getById(1L).getTermEndTime());
    }

    @Test
    void nonexistentCouponIsRejected() {
        assertThrows(BadRequestException.class, () -> service.issueCoupon(999L, relativeTerm()));
    }

    @Test
    void missingObtainWayIsRejectedWithoutChangingState() {
        service.lambdaUpdate().eq(Coupon::getId, 1L).set(Coupon::getObtainWay, null).update();
        assertRejected(relativeTerm());
    }

    @Test
    void everyNonDraftStateIsRejectedWithoutOverwritingConfiguration() {
        for (CouponStatus status : new CouponStatus[]{CouponStatus.UN_ISSUE, CouponStatus.ISSUING,
                CouponStatus.FINISHED, CouponStatus.PAUSE}) {
            service.lambdaUpdate().eq(Coupon::getId, 1L).set(Coupon::getStatus, status)
                    .set(Coupon::getIssueEndTime, now.plusDays(10)).update();
            assertThrows(BadRequestException.class, () -> service.issueCoupon(1L, relativeTerm()));
            assertEquals(status, service.getById(1L).getStatus());
            assertEquals(now.plusDays(10), service.getById(1L).getIssueEndTime());
        }
    }

    @Test
    void repeatedRequestCannotOverwriteSuccessfulIssue() {
        service.issueCoupon(1L, relativeTerm());
        CouponIssueDTO second = relativeTerm();
        second.setIssueEndTime(now.plusDays(20));
        assertThrows(BadRequestException.class, () -> service.issueCoupon(1L, second));
        assertEquals(now.plusDays(2), service.getById(1L).getIssueEndTime());
    }

    @Test
    void pastStartAndInvalidIssueEndsAreRejected() {
        CouponIssueDTO past = relativeTerm();
        past.setIssueBeginTime(now.minusSeconds(1));
        assertRejected(past);
        for (LocalDateTime end : new LocalDateTime[]{null, now.minusSeconds(1), now}) {
            CouponIssueDTO dto = relativeTerm();
            dto.setIssueEndTime(end);
            assertRejected(dto);
        }
        CouponIssueDTO reversed = relativeTerm();
        reversed.setIssueBeginTime(now.plusDays(3));
        assertRejected(reversed);
        reversed.setIssueEndTime(reversed.getIssueBeginTime());
        assertRejected(reversed);
    }

    @Test
    void invalidAndMixedValidityModesAreRejected() {
        for (Integer days : new Integer[]{null, -1}) {
            CouponIssueDTO dto = relativeTerm();
            dto.setTermDays(days);
            assertRejected(dto);
        }
        CouponIssueDTO mixed = relativeTerm();
        mixed.setTermBeginTime(now);
        assertRejected(mixed);
        mixed.setTermBeginTime(null);
        mixed.setTermEndTime(now.plusDays(10));
        assertRejected(mixed);
    }

    @Test
    void missingReversedOrTooShortFixedTermIsRejected() {
        CouponIssueDTO dto = fixedTerm();
        dto.setTermBeginTime(null);
        assertRejected(dto);
        dto = fixedTerm();
        dto.setTermEndTime(null);
        assertRejected(dto);
        dto = fixedTerm();
        dto.setTermEndTime(dto.getTermBeginTime());
        assertRejected(dto);
        dto.setTermEndTime(dto.getTermBeginTime().minusSeconds(1));
        assertRejected(dto);
        dto = fixedTerm();
        dto.setTermEndTime(dto.getIssueEndTime().minusSeconds(1));
        assertRejected(dto);
    }

    @Test
    void taskActivatesDueCouponsAndFinishesExpiredCouponsIncludingPaused() {
        service.save(draft(2L).setStatus(CouponStatus.UN_ISSUE)
                .setIssueBeginTime(now.minusHours(1)).setIssueEndTime(now.plusHours(1)));
        service.save(draft(3L).setStatus(CouponStatus.UN_ISSUE)
                .setIssueBeginTime(now.minusDays(2)).setIssueEndTime(now.minusDays(1)));
        service.save(draft(4L).setStatus(CouponStatus.ISSUING).setIssueEndTime(now.minusMinutes(1)));
        service.save(draft(5L).setStatus(CouponStatus.PAUSE).setIssueEndTime(now.minusMinutes(1)));
        service.save(draft(6L).setStatus(CouponStatus.PAUSE)
                .setIssueBeginTime(now.minusHours(1)).setIssueEndTime(now.plusHours(1)));
        service.save(draft(7L).setStatus(CouponStatus.FINISHED).setIssueEndTime(now.plusHours(1)));
        service.refreshCouponStatus();
        assertEquals(CouponStatus.DRAFT, service.getById(1L).getStatus());
        assertEquals(CouponStatus.ISSUING, service.getById(2L).getStatus());
        for (long id : new long[]{3L, 4L, 5L, 7L}) {
            assertEquals(CouponStatus.FINISHED, service.getById(id).getStatus());
        }
        assertEquals(CouponStatus.PAUSE, service.getById(6L).getStatus());
        LocalDateTime updateTime = service.getById(2L).getUpdateTime();
        service.refreshCouponStatus();
        assertEquals(updateTime, service.getById(2L).getUpdateTime());
        assertEquals(2, service.getById(2L).getIssueNum());
    }

    @Test
    void dueEndBoundaryTakesPriorityOverActivation() {
        service.save(draft(2L).setStatus(CouponStatus.UN_ISSUE)
                .setIssueBeginTime(now.minusHours(1)).setIssueEndTime(now));
        service.refreshCouponStatus();
        assertEquals(CouponStatus.FINISHED, service.getById(2L).getStatus());
    }

    @Test
    void twoRequestsReadingDraftConcurrentlyHaveExactlyOneWinner() throws Exception {
        CyclicBarrier bothReadDraft = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> issueConcurrently(bothReadDraft, 30));
            Future<Boolean> second = executor.submit(() -> issueConcurrently(bothReadDraft, 40));
            boolean firstWon = first.get(10, TimeUnit.SECONDS);
            boolean secondWon = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon);
            session.clearCache();
            Coupon actual = service.getById(1L);
            assertEquals(CouponStatus.ISSUING, actual.getStatus());
            assertEquals(firstWon ? 30 : 40, actual.getTermDays());
            assertEquals(2, actual.getIssueNum());
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean issueConcurrently(CyclicBarrier barrier, int days) {
        try (SqlSession transaction = factory.openSession()) {
            CouponServiceImpl concurrentService = new TestCouponService(transaction.getMapper(CouponMapper.class)) {
                @Override
                public Coupon getById(Serializable id) {
                    Coupon coupon = super.getById(id);
                    assertEquals(CouponStatus.DRAFT, coupon.getStatus());
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    return coupon;
                }
            };
            CouponIssueDTO dto = relativeTerm();
            dto.setTermDays(days);
            try {
                concurrentService.issueCoupon(1L, dto);
                transaction.commit();
                return true;
            } catch (BadRequestException e) {
                transaction.rollback();
                assertEquals("优惠券状态已变更，请刷新后重试", e.getMessage());
                return false;
            }
        }
    }

    private void assertRejected(CouponIssueDTO dto) {
        assertThrows(BadRequestException.class, () -> service.issueCoupon(1L, dto));
        Coupon actual = service.getById(1L);
        assertEquals(CouponStatus.DRAFT, actual.getStatus());
        assertNull(actual.getIssueBeginTime());
        assertNull(actual.getIssueEndTime());
    }

    private CouponIssueDTO relativeTerm() {
        CouponIssueDTO dto = new CouponIssueDTO();
        dto.setIssueEndTime(now.plusDays(2));
        dto.setTermDays(30);
        return dto;
    }

    private CouponIssueDTO fixedTerm() {
        CouponIssueDTO dto = relativeTerm();
        dto.setTermDays(0);
        dto.setTermBeginTime(now);
        dto.setTermEndTime(now.plusDays(3));
        return dto;
    }

    private Coupon draft(Long id) {
        return new Coupon().setId(id).setName("测试优惠券").setStatus(CouponStatus.DRAFT)
                .setObtainWay(ObtainType.PUBLIC).setDiscountType(DiscountType.RATE_DISCOUNT)
                .setDiscountValue(80).setTotalNum(100).setIssueNum(2).setUsedNum(1).setUserLimit(2);
    }

    private static class TestCouponService extends CouponServiceImpl {
        TestCouponService(CouponMapper mapper) {
            super(null, null, null);
            this.baseMapper = mapper;
        }
    }
}
