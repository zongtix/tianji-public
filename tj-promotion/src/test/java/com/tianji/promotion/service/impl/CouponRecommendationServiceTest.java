package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.constants.CourseStatus;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.dto.CouponRecommendationDTO;
import com.tianji.promotion.domain.vo.CouponRecommendationVO;
import com.tianji.promotion.domain.vo.CouponUsageVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.CouponScopeMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponRecommendationServiceTest {

    private static SqlSessionFactory factory;
    private static DataSource dataSource;
    private SqlSession session;
    private JdbcTemplate jdbc;
    private CourseClient courseClient;
    private CouponRecommendationServiceImpl service;
    private Map<Long, CourseSimpleInfoDTO> courses;
    private LocalDateTime now;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:coupon_recommendation_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        try (java.sql.Connection connection = h2.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
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
            statement.execute("CREATE TABLE coupon_scope (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "type INT, coupon_id BIGINT, biz_id BIGINT)");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), h2));
        configuration.getTypeHandlerRegistry().register(CouponStatus.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(DiscountType.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(ObtainType.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(UserCouponStatus.class, MybatisEnumTypeHandler.class);
        configuration.addMapper(CouponMapper.class);
        configuration.addMapper(UserCouponMapper.class);
        configuration.addMapper(CouponScopeMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() {
        session = factory.openSession();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM user_coupon");
        jdbc.update("DELETE FROM coupon_scope");
        jdbc.update("DELETE FROM coupon");
        now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        courses = new HashMap<>();
        courseClient = mock(CourseClient.class);
        when(courseClient.getSimpleInfoList(any())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<CourseSimpleInfoDTO> result = new ArrayList<>();
            for (Long id : ids) {
                CourseSimpleInfoDTO course = courses.get(id);
                if (course != null) {
                    result.add(course);
                }
            }
            return result;
        });
        service = new CouponRecommendationServiceImpl(courseClient,
                session.getMapper(UserCouponMapper.class),
                session.getMapper(CouponMapper.class),
                session.getMapper(CouponScopeMapper.class));
        UserContext.setUser(2L);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        session.close();
    }

    @Test
    void picksLargestSingleDiscountAndDistinguishesTemplateFromUserCoupon() {
        course(101L, 10000);
        coupon(1L, DiscountType.PRICE_DISCOUNT, 1500, 10000, 0, false);
        coupon(2L, DiscountType.RATE_DISCOUNT, 80, 10000, 0, false);
        userCoupon(11L, 2L, 1L);
        userCoupon(12L, 2L, 2L);

        CouponRecommendationVO result = recommend(101L);

        assertEquals(10000, result.getOriginalAmount());
        assertEquals(2000, result.getTotalDiscount());
        assertEquals(8000, result.getPayableAmount());
        assertEquals(List.of(12L), result.getUserCouponIds());
        assertEquals(List.of(2L), result.getCouponIds());
        assertEquals(1, result.getUsages().size());
        CouponUsageVO usage = result.getUsages().get(0);
        assertEquals(12L, usage.getUserCouponId());
        assertEquals(2L, usage.getCouponId());
        assertEquals(10000, usage.getApplicableAmount());
        assertEquals(2000, usage.getDiscountAmount());
        assertTrue(usage.getRuleDescription().contains("8折"));
    }

    @Test
    void combinesRateBeforeFixedAmountToMaximizeDiscount() {
        course(101L, 10000);
        coupon(1L, DiscountType.RATE_DISCOUNT, 80, 0, 0, false);
        coupon(2L, DiscountType.NO_THRESHOLD, 1500, 0, 0, false);
        userCoupon(11L, 2L, 1L);
        userCoupon(12L, 2L, 2L);

        CouponRecommendationVO result = recommend(101L);

        assertEquals(3500, result.getTotalDiscount());
        assertEquals(6500, result.getPayableAmount());
        assertEquals(List.of(11L, 12L), result.getUserCouponIds());
        assertEquals(List.of(1L, 2L), result.getCouponIds());
        assertEquals(10000, result.getUsages().get(0).getApplicableAmount());
        assertEquals(2000, result.getUsages().get(0).getDiscountAmount());
        assertEquals(8000, result.getUsages().get(1).getApplicableAmount());
        assertEquals(1500, result.getUsages().get(1).getDiscountAmount());
        assertEquals(result.getUserCouponIds().size(), new HashSet<>(result.getUserCouponIds()).size());
    }

    @Test
    void scopedCouponUsesOnlyMatchedCourseAmountAndRespectsThreshold() {
        course(101L, 6000, 10L);
        course(102L, 4000, 20L);
        coupon(1L, DiscountType.PRICE_DISCOUNT, 1000, 5000, 0, true);
        scope(1L, 1L, 2, 101L);
        coupon(2L, DiscountType.PRICE_DISCOUNT, 1500, 4500, 0, true);
        scope(2L, 2L, 1, 20L);
        coupon(3L, DiscountType.RATE_DISCOUNT, 90, 0, 0, false);
        userCoupon(11L, 2L, 1L);
        userCoupon(12L, 2L, 2L);
        userCoupon(13L, 2L, 3L);

        CouponRecommendationVO result = recommend(101L, 102L);

        assertEquals(2000, result.getTotalDiscount());
        assertEquals(List.of(13L, 11L), result.getUserCouponIds());
        assertEquals(List.of(3L, 1L), result.getCouponIds());
        assertEquals(10000, result.getUsages().get(0).getApplicableAmount());
        assertEquals(5400, result.getUsages().get(1).getApplicableAmount());
        assertEquals(1000, result.getUsages().get(1).getDiscountAmount());
        assertEquals(List.of(101L), result.getUsages().get(1).getMatchedCourseIds());
        assertEquals(List.of(101L), result.getUsages().get(1).getScopeBizIds());
        assertFalse(result.getUserCouponIds().contains(12L));
    }

    @Test
    void onlyCurrentUsersUnusedUnexpiredCouponsAreCandidates() {
        course(101L, 10000);
        coupon(1L, DiscountType.NO_THRESHOLD, 1000, 0, 0, false);
        userCoupon(11L, 2L, 1L);
        userCoupon(12L, 2L, 1L, UserCouponStatus.USED, now.minusDays(2), now.plusDays(1));
        userCoupon(13L, 2L, 1L, UserCouponStatus.EXPIRED, now.minusDays(2), now.minusDays(1));
        userCoupon(14L, 2L, 1L, UserCouponStatus.UNUSED, now.plusDays(1), now.plusDays(2));
        userCoupon(15L, 3L, 1L);

        CouponRecommendationVO result = recommend(101L);

        assertEquals(1000, result.getTotalDiscount());
        assertEquals(List.of(11L), result.getUserCouponIds());
    }

    @Test
    void perPriceDiscountMultipliesByThresholdUnits() {
        course(101L, 25000);
        coupon(1L, DiscountType.PER_PRICE_DISCOUNT, 1500, 10000, 0, false);
        userCoupon(11L, 2L, 1L);

        CouponRecommendationVO result = recommend(101L);

        assertEquals(3000, result.getTotalDiscount());
        assertEquals(22000, result.getPayableAmount());
    }

    @Test
    void maximumDiscountCapsAndOrderAmountClampsDiscount() {
        course(101L, 25000);
        coupon(1L, DiscountType.PER_PRICE_DISCOUNT, 1500, 10000, 2000, false);
        userCoupon(11L, 2L, 1L);
        assertEquals(2000, recommend(101L).getTotalDiscount());

        jdbc.update("DELETE FROM user_coupon");
        coupon(2L, DiscountType.NO_THRESHOLD, 50000, 0, 0, false);
        userCoupon(12L, 2L, 2L);
        CouponRecommendationVO clamped = recommend(101L);
        assertEquals(25000, clamped.getTotalDiscount());
        assertEquals(0, clamped.getPayableAmount());
    }

    @Test
    void invalidRulesMissingTemplatesAndUnmetThresholdsAreSkipped() {
        course(101L, 10000);
        coupon(1L, DiscountType.RATE_DISCOUNT, 100, 0, 0, false);
        coupon(2L, DiscountType.PER_PRICE_DISCOUNT, 1000, 0, 0, false);
        coupon(3L, DiscountType.PRICE_DISCOUNT, 1000, 5000, 0, true);
        coupon(4L, DiscountType.PRICE_DISCOUNT, 1000, 20000, 0, false);
        userCoupon(11L, 2L, 1L);
        userCoupon(12L, 2L, 2L);
        userCoupon(13L, 2L, 3L);
        userCoupon(14L, 2L, 4L);
        userCoupon(15L, 2L, 999L);

        CouponRecommendationVO result = recommend(101L);

        assertEquals(0, result.getTotalDiscount());
        assertEquals(10000, result.getPayableAmount());
        assertTrue(result.getUserCouponIds().isEmpty());
        assertTrue(result.getUsages().isEmpty());
    }

    @Test
    void planUsesAtMostThreeDistinctUserCoupons() {
        course(101L, 10000);
        for (long id = 1; id <= 4; id++) {
            coupon(id, DiscountType.NO_THRESHOLD, 1000, 0, 0, false);
            userCoupon(10L + id, 2L, id);
        }

        CouponRecommendationVO result = recommend(101L);

        assertEquals(3000, result.getTotalDiscount());
        assertEquals(List.of(11L, 12L, 13L), result.getUserCouponIds());
        assertEquals(result.getUserCouponIds().size(), new HashSet<>(result.getUserCouponIds()).size());
    }

    @Test
    void noValidCouponReturnsZeroPlan() {
        course(101L, 10000);
        CouponRecommendationVO result = recommend(101L);
        assertEquals(0, result.getTotalDiscount());
        assertEquals(10000, result.getPayableAmount());
        assertTrue(result.getUserCouponIds().isEmpty());
    }

    @Test
    void loginIsRequired() {
        UserContext.removeUser();
        assertThrows(UnauthorizedException.class, () -> recommend(101L));
    }

    @Test
    void emptyAndDuplicateCourseIdsAreRejected() {
        assertThrows(BadRequestException.class, this::recommend);
        assertThrows(BadRequestException.class, () -> recommend(101L, 101L));
    }

    @Test
    void tooManyCoursesAreRejectedAtServiceBoundary() {
        Long[] courseIds = new Long[11];
        for (int index = 0; index < courseIds.length; index++) {
            courseIds[index] = (long) (index + 1);
        }
        assertThrows(BadRequestException.class, () -> recommend(courseIds));
    }

    @Test
    void missingUnsellableExpiredOrPricedAbnormallyCoursesAreRejected() {
        assertThrows(BadRequestException.class, () -> recommend(999L));

        course(101L, 10000);
        courses.get(101L).setStatus(CourseStatus.DOWN_SHELF.getValue());
        assertThrows(BadRequestException.class, () -> recommend(101L));

        courses.get(101L).setStatus(CourseStatus.SHELF.getValue());
        courses.get(101L).setPurchaseEndTime(now.minusDays(1));
        assertThrows(BadRequestException.class, () -> recommend(101L));

        courses.get(101L).setPurchaseEndTime(now.plusDays(1));
        courses.get(101L).setPrice(-1);
        assertThrows(BadRequestException.class, () -> recommend(101L));
    }

    @Test
    void orderTotalBeyondIntegerRangeIsRejectedInsteadOfOverflowing() {
        course(101L, Integer.MAX_VALUE);
        course(102L, 1);
        assertThrows(BadRequestException.class, () -> recommend(101L, 102L));
    }

    private void course(long id, int price, Long... categoryIds) {
        CourseSimpleInfoDTO course = new CourseSimpleInfoDTO();
        course.setId(id);
        course.setName("课程" + id);
        course.setPrice(price);
        course.setStatus(CourseStatus.SHELF.getValue());
        course.setPurchaseEndTime(now.plusDays(30));
        if (categoryIds.length > 0) {
            course.setFirstCateId(categoryIds[0]);
        }
        if (categoryIds.length > 1) {
            course.setSecondCateId(categoryIds[1]);
        }
        if (categoryIds.length > 2) {
            course.setThirdCateId(categoryIds[2]);
        }
        courses.put(id, course);
    }

    private void coupon(long id, DiscountType type, int value, int threshold, int max, boolean specific) {
        jdbc.update("INSERT INTO coupon (id, `name`, discount_type, `specific`, discount_value, "
                        + "threshold_amount, max_discount_amount) VALUES (?,?,?,?,?,?,?)",
                id, "券" + id, type.getValue(), specific, value, threshold, max);
    }

    private void userCoupon(long id, long userId, long couponId) {
        userCoupon(id, userId, couponId, UserCouponStatus.UNUSED, now.minusDays(1), now.plusDays(1));
    }

    private void userCoupon(long id, long userId, long couponId, UserCouponStatus status,
                            LocalDateTime begin, LocalDateTime end) {
        jdbc.update("INSERT INTO user_coupon (id, user_id, coupon_id, term_begin_time, "
                        + "term_end_time, status) VALUES (?,?,?,?,?,?)",
                id, userId, couponId, begin, end, status.getValue());
    }

    private void scope(long id, long couponId, int type, long bizId) {
        jdbc.update("INSERT INTO coupon_scope (id, type, coupon_id, biz_id) VALUES (?,?,?,?)",
                id, type, couponId, bizId);
    }

    private CouponRecommendationVO recommend(Long... courseIds) {
        CouponRecommendationDTO dto = new CouponRecommendationDTO();
        dto.setCourseIds(List.of(courseIds));
        return service.recommend(dto);
    }
}
