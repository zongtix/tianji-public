package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.IUserCouponService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CouponListQueryTest {
    private static SqlSessionFactory factory;
    private SqlSession session;
    private CouponServiceImpl service;
    private UserCouponServiceImpl userCoupons;
    private LocalDateTime now;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:coupon_list_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE coupon (id BIGINT PRIMARY KEY, `name` VARCHAR(100), type INT DEFAULT 1, "
                    + "discount_type INT, `specific` BOOLEAN, discount_value INT, threshold_amount INT, "
                    + "max_discount_amount INT, obtain_way INT, issue_begin_time TIMESTAMP, issue_end_time TIMESTAMP, "
                    + "term_days INT, term_begin_time TIMESTAMP, term_end_time TIMESTAMP, status INT, total_num INT, "
                    + "issue_num INT, used_num INT, user_limit INT, ext_param VARCHAR(100), create_time TIMESTAMP, "
                    + "update_time TIMESTAMP, creater BIGINT, updater BIGINT)");
            statement.execute("CREATE TABLE user_coupon (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "user_id BIGINT NOT NULL, coupon_id BIGINT NOT NULL, term_begin_time TIMESTAMP, "
                    + "term_end_time TIMESTAMP NOT NULL, used_time TIMESTAMP, status INT NOT NULL DEFAULT 1, "
                    + "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeHandlerRegistry().register(CouponStatus.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(DiscountType.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(ObtainType.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(UserCouponStatus.class, MybatisEnumTypeHandler.class);
        configuration.addMapper(CouponMapper.class);
        configuration.addMapper(UserCouponMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() {
        session = factory.openSession();
        userCoupons = new TestUserCouponService(session.getMapper(UserCouponMapper.class));
        service = new TestCouponService(session.getMapper(CouponMapper.class), userCoupons);
        now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        UserContext.setUser(2L);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        session.close();
    }

    @Test
    void onlyPublicIssuingCouponsInsideIssueWindowAreListed() {
        service.save(coupon(1L));
        service.save(coupon(2L).setStatus(CouponStatus.DRAFT));
        service.save(coupon(3L).setStatus(CouponStatus.UN_ISSUE));
        service.save(coupon(4L).setStatus(CouponStatus.FINISHED));
        service.save(coupon(5L).setStatus(CouponStatus.PAUSE));
        service.save(coupon(6L).setObtainWay(ObtainType.ISSUE));
        service.save(coupon(7L).setIssueBeginTime(now.plusDays(1)));
        service.save(coupon(8L).setIssueEndTime(now.minusDays(1)));
        assertEquals(Arrays.asList(1L), ids(service.queryIssuingCoupons()));
    }

    @Test
    void exhaustedCouponsRemainVisibleAndRulesAreCopied() {
        service.save(coupon(1L).setIssueNum(100));
        CouponVO vo = service.queryIssuingCoupons().get(0);
        assertFalse(vo.getAvailable());
        assertFalse(vo.getReceived());
        assertEquals("测试优惠券", vo.getName());
        assertEquals(Boolean.FALSE, vo.getSpecific());
        assertEquals(DiscountType.RATE_DISCOUNT, vo.getDiscountType());
        assertEquals(80, vo.getDiscountValue());
        assertEquals(1000, vo.getThresholdAmount());
        assertEquals(500, vo.getMaxDiscountAmount());
        assertEquals(7, vo.getTermDays());
        userCoupons.save(record(1L, 2L));
        CouponVO held = service.queryIssuingCoupons().get(0);
        assertFalse(held.getAvailable());
        assertTrue(held.getReceived());
    }

    @Test
    void usedAndExpiredRecordsStillConsumeUserLimit() {
        service.save(coupon(1L).setUserLimit(2));
        userCoupons.save(record(1L, 2L).setStatus(UserCouponStatus.USED));
        userCoupons.save(record(1L, 2L).setStatus(UserCouponStatus.EXPIRED));
        CouponVO vo = service.queryIssuingCoupons().get(0);
        assertFalse(vo.getAvailable());
        assertFalse(vo.getReceived());
    }

    @Test
    void receivedRequiresAnUnusedCouponWithinItsOwnValidityPeriod() {
        for (long id = 1; id <= 5; id++) {
            service.save(coupon(id));
        }
        userCoupons.save(record(1L, 2L));
        userCoupons.save(record(2L, 2L).setTermEndTime(now.minusDays(1)));
        userCoupons.save(record(3L, 2L).setTermBeginTime(now.plusDays(1)));
        userCoupons.save(record(4L, 2L).setStatus(UserCouponStatus.USED));
        userCoupons.save(record(5L, 2L).setTermBeginTime(null));
        List<CouponVO> result = service.queryIssuingCoupons();
        assertEquals(Arrays.asList(5L, 1L), result.stream().filter(CouponVO::getReceived)
                .map(CouponVO::getId).collect(Collectors.toList()));
        assertTrue(result.stream().noneMatch(CouponVO::getAvailable));
    }

    @Test
    void userRecordsAreIsolatedAndReceivingCanRemainAvailableBelowLimit() {
        service.save(coupon(1L));
        service.save(coupon(2L).setUserLimit(2));
        userCoupons.save(record(1L, 129L));
        userCoupons.save(record(2L, 2L));
        userCoupons.save(record(999L, 2L));
        List<CouponVO> result = service.queryIssuingCoupons();
        assertEquals(Arrays.asList(2L, 1L), ids(result));
        assertTrue(result.get(0).getAvailable());
        assertTrue(result.get(0).getReceived());
        assertTrue(result.get(1).getAvailable());
        assertFalse(result.get(1).getReceived());
    }

    @Test
    void publicListWithoutUserContextDoesNotReadOtherUsersRecords() {
        service.save(coupon(1L));
        userCoupons.save(record(1L, 2L));
        UserContext.removeUser();
        CouponVO vo = service.queryIssuingCoupons().get(0);
        assertTrue(vo.getAvailable());
        assertFalse(vo.getReceived());
    }

    @Test
    void emptyResultAndStableIssueTimeOrderAreSupported() {
        assertTrue(service.queryIssuingCoupons().isEmpty());
        service.save(coupon(1L));
        service.save(coupon(2L));
        service.save(coupon(3L).setIssueBeginTime(now.minusDays(2)));
        assertEquals(Arrays.asList(2L, 1L, 3L), ids(service.queryIssuingCoupons()));
    }

    private Coupon coupon(Long id) {
        return new Coupon().setId(id).setName("测试优惠券").setStatus(CouponStatus.ISSUING)
                .setObtainWay(ObtainType.PUBLIC).setIssueBeginTime(now.minusDays(1))
                .setIssueEndTime(now.plusDays(2)).setTotalNum(100).setIssueNum(0).setUserLimit(1)
                .setSpecific(false).setDiscountType(DiscountType.RATE_DISCOUNT).setDiscountValue(80)
                .setThresholdAmount(1000).setMaxDiscountAmount(500).setTermDays(7);
    }

    private UserCoupon record(Long couponId, Long userId) {
        return new UserCoupon().setCouponId(couponId).setUserId(userId).setStatus(UserCouponStatus.UNUSED)
                .setTermBeginTime(now.minusDays(1)).setTermEndTime(now.plusDays(3));
    }

    private List<Long> ids(List<CouponVO> coupons) {
        return coupons.stream().map(CouponVO::getId).collect(Collectors.toList());
    }

    private static class TestCouponService extends CouponServiceImpl {
        TestCouponService(CouponMapper mapper, IUserCouponService userCoupons) {
            super(null, null, userCoupons);
            this.baseMapper = mapper;
        }
    }

    private static class TestUserCouponService extends UserCouponServiceImpl {
        TestUserCouponService(UserCouponMapper mapper) {
            super(null, null);
            this.baseMapper = mapper;
        }
    }
}
