package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.UserCouponPageVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class UserCouponPageQueryTest {
    private static SqlSessionFactory sessionFactory;
    private static JdbcDataSource dataSource;
    private SqlSession session;
    private UserCouponServiceImpl service;
    private CouponMapper couponMapper;

    @BeforeAll
    static void createDatabase() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:user_coupon_page_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE coupon (id BIGINT PRIMARY KEY, `name` VARCHAR(100), type INT, "
                    + "discount_type INT, `specific` BOOLEAN, discount_value INT, threshold_amount INT, "
                    + "max_discount_amount INT, obtain_way INT, issue_begin_time TIMESTAMP, issue_end_time TIMESTAMP, "
                    + "term_days INT, term_begin_time TIMESTAMP, term_end_time TIMESTAMP, status INT, total_num INT, "
                    + "issue_num INT, used_num INT, user_limit INT, ext_param VARCHAR(100), create_time TIMESTAMP, "
                    + "update_time TIMESTAMP, creater BIGINT, updater BIGINT)");
            statement.execute("CREATE TABLE user_coupon (id BIGINT PRIMARY KEY, user_id BIGINT, coupon_id BIGINT, "
                    + "term_begin_time TIMESTAMP, term_end_time TIMESTAMP NOT NULL, used_time TIMESTAMP, status INT, "
                    + "create_time TIMESTAMP, update_time TIMESTAMP)");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeHandlerRegistry().register(DiscountType.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(CouponStatus.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(ObtainType.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(UserCouponStatus.class, MybatisEnumTypeHandler.class);
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(200L);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        configuration.addInterceptor(interceptor);
        configuration.addMapper(CouponMapper.class);
        configuration.addMapper(UserCouponMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM user_coupon");
            statement.execute("DELETE FROM coupon");
            statement.execute("INSERT INTO coupon (id,`name`,discount_type,`specific`,threshold_amount,discount_value,"
                    + "max_discount_amount,term_days,term_begin_time,term_end_time,status) VALUES "
                    + "(101,'折扣券',2,TRUE,10000,80,3000,7,'2030-01-01','2030-02-01',4),"
                    + "(102,'满减券',4,FALSE,10000,2000,0,30,'2030-01-01','2030-02-01',5)");
        }
        LocalDateTime now = LocalDateTime.now();
        insert(11, 2, 101, 1, now.minusDays(2), now.plusDays(5), 1);
        insert(12, 2, 101, 1, now.plusDays(1), now.plusDays(10), 1);
        insert(13, 2, 102, 2, now.minusDays(3), now.minusDays(1), 2);
        insert(14, 2, 102, 1, now.minusDays(3), now.minusDays(1), 3);
        insert(15, 2, 102, 3, now.minusDays(2), now.plusDays(1), 4);
        insert(16, 2, 103, 1, now.minusDays(1), now.plusDays(2), 5);
        insert(21, 3, 101, 1, now.minusDays(1), now.plusDays(2), 6);
        insert(22, 3, 102, 1, now.minusDays(3), now.minusDays(1), 7);
        insert(23, 3, 102, 2, now.minusDays(3), now.minusDays(1), 8);
        insert(24, 3, 102, 3, now.minusDays(1), now.plusDays(2), 9);
        session = sessionFactory.openSession(true);
        couponMapper = spy(session.getMapper(CouponMapper.class));
        service = new TestUserCouponService(session.getMapper(UserCouponMapper.class), couponMapper);
        UserContext.setUser(2L);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        session.close();
    }

    @Test
    void defaultPageContainsOnlyOwnUnexpiredUnusedCouponsInStableOrder() {
        PageDTO<UserCouponPageVO> page = service.queryUserCouponPage(new UserCouponQuery());
        assertEquals(3L, page.getTotal());
        assertEquals(1L, page.getPages());
        assertEquals(Arrays.asList(16L, 12L, 11L), ids(page));
    }

    @Test
    void paginationRetainsTotalsAndBreaksTimestampTiesByUserCouponId() {
        UserCouponQuery query = new UserCouponQuery();
        query.setPageSize(2);
        PageDTO<UserCouponPageVO> first = service.queryUserCouponPage(query);
        assertEquals(Arrays.asList(16L, 12L), ids(first));
        assertEquals(3L, first.getTotal());
        assertEquals(2L, first.getPages());
        query.setPageNo(2);
        assertEquals(Arrays.asList(11L), ids(service.queryUserCouponPage(query)));
    }

    @Test
    void expiredTabCombinesStoredExpiryAndElapsedUnusedValidityWithoutOtherUsers() {
        PageDTO<UserCouponPageVO> page = service.queryUserCouponPage(new UserCouponQuery().setStatus(3));
        assertEquals(Arrays.asList(15L, 14L), ids(page));
        assertTrue(page.getList().stream().allMatch(record -> record.getStatus() == UserCouponStatus.EXPIRED));
    }

    @Test
    void usedCouponsRemainUsedAfterTheirValidityEnds() {
        PageDTO<UserCouponPageVO> page = service.queryUserCouponPage(new UserCouponQuery().setStatus(2));
        assertEquals(Arrays.asList(13L), ids(page));
        assertEquals(UserCouponStatus.USED, page.getList().get(0).getStatus());
        assertNotNull(page.getList().get(0).getUsedTime());
    }

    @Test
    void userContextControlsEveryStatusFilter() {
        UserContext.setUser(3L);
        assertEquals(Arrays.asList(21L), ids(service.queryUserCouponPage(new UserCouponQuery())));
        assertEquals(Arrays.asList(23L), ids(service.queryUserCouponPage(new UserCouponQuery().setStatus(2))));
        assertEquals(Arrays.asList(24L, 22L), ids(service.queryUserCouponPage(new UserCouponQuery().setStatus(3))));
    }

    @Test
    void rulesComeFromTemplateButIdsAndValidityComeFromUserCoupon() {
        UserCouponPageVO record = service.queryUserCouponPage(new UserCouponQuery()).getList().get(1);
        assertEquals(12L, record.getId());
        assertEquals(101L, record.getCouponId());
        assertEquals("折扣券", record.getName());
        assertEquals(Boolean.TRUE, record.getSpecific());
        assertEquals(DiscountType.RATE_DISCOUNT, record.getDiscountType());
        assertEquals(80, record.getDiscountValue());
        assertEquals(10000, record.getThresholdAmount());
        assertEquals(3000, record.getMaxDiscountAmount());
        assertTrue(record.getTermBeginTime().isAfter(LocalDateTime.now()));
        assertEquals(service.getById(12L).getTermEndTime(), record.getTermEndTime());
        assertNotEquals(LocalDateTime.of(2030, 2, 1, 0, 0), record.getTermEndTime());
    }

    @Test
    void endedOrPausedTemplateDoesNotHideLegallyOwnedCoupons() {
        assertEquals(Arrays.asList(16L, 12L, 11L), ids(service.queryUserCouponPage(new UserCouponQuery())));
        assertEquals(Arrays.asList(13L), ids(service.queryUserCouponPage(new UserCouponQuery().setStatus(2))));
    }

    @Test
    void missingTemplatePreservesPaginationWithoutInventingDiscounts() {
        PageDTO<UserCouponPageVO> page = service.queryUserCouponPage(new UserCouponQuery());
        UserCouponPageVO record = page.getList().get(0);
        assertEquals(3L, page.getTotal());
        assertEquals(16L, record.getId());
        assertEquals(103L, record.getCouponId());
        assertEquals("优惠券信息不可用", record.getName());
        assertNull(record.getDiscountType());
        assertNull(record.getDiscountValue());
        assertNotNull(record.getTermEndTime());
    }

    @Test
    void templatesAreLoadedOncePerPageNotOncePerUserCoupon() {
        service.queryUserCouponPage(new UserCouponQuery());
        verify(couponMapper, times(1)).selectByIds(anyCollection());
    }

    @Test
    void emptyOwnerReturnsEmptyListWithoutTemplateQuery() {
        UserContext.setUser(99L);
        PageDTO<UserCouponPageVO> page = service.queryUserCouponPage(new UserCouponQuery());
        assertEquals(0L, page.getTotal());
        assertEquals(0L, page.getPages());
        assertTrue(page.getList().isEmpty());
        verify(couponMapper, never()).selectByIds(anyCollection());
    }

    @Test
    void beyondLastPageKeepsTotalsAndReturnsEmptyList() {
        UserCouponQuery query = new UserCouponQuery();
        query.setPageSize(2);
        query.setPageNo(100);
        PageDTO<UserCouponPageVO> page = service.queryUserCouponPage(query);
        assertEquals(3L, page.getTotal());
        assertEquals(2L, page.getPages());
        assertTrue(page.getList().isEmpty());
    }

    @Test
    void inheritedSortInputCannotReachSqlOrdering() {
        UserCouponQuery query = new UserCouponQuery();
        query.setSortBy("id desc; DROP TABLE user_coupon");
        query.setIsAsc(true);
        assertEquals(Arrays.asList(16L, 12L, 11L), ids(service.queryUserCouponPage(query)));
    }

    @Test
    void anonymousAndNonPositiveIdentitiesCannotQuery() {
        for (Long user : new Long[]{null, 0L, -1L}) {
            UserContext.setUser(user);
            assertThrows(UnauthorizedException.class, () -> service.queryUserCouponPage(new UserCouponQuery()));
        }
        verifyNoInteractions(couponMapper);
    }

    @Test
    void invalidStatusIsRejectedEvenForDirectServiceCalls() {
        for (Integer status : new Integer[]{null, 0, 4}) {
            assertThrows(BadRequestException.class, () -> service.queryUserCouponPage(new UserCouponQuery().setStatus(status)));
        }
    }

    @Test
    void queryDoesNotMutateExpiredRecords() {
        service.queryUserCouponPage(new UserCouponQuery().setStatus(3));
        assertEquals(UserCouponStatus.UNUSED, service.getById(14L).getStatus());
    }

    @Test
    void existingFrontendPageSizeIsAcceptedAndGlobalLimitRemainsTwoHundred() throws Exception {
        for (long index = 1000; index < 1205; index++) {
            insert(index, 2, 101, 1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), 0);
        }
        UserCouponQuery query = new UserCouponQuery();
        query.setPageSize(1000);
        PageDTO<UserCouponPageVO> page = service.queryUserCouponPage(query);
        assertEquals(208L, page.getTotal());
        assertEquals(2L, page.getPages());
        assertEquals(200, page.getList().size());
    }

    private static void insert(long id, long user, long coupon, int status, LocalDateTime begin, LocalDateTime end, int createDay) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user_coupon (id,user_id,coupon_id,status,term_begin_time,term_end_time,used_time,create_time) VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, id);
            statement.setLong(2, user);
            statement.setLong(3, coupon);
            statement.setInt(4, status);
            statement.setObject(5, begin);
            statement.setObject(6, end);
            statement.setObject(7, status == 2 ? begin.plusHours(1) : null);
            statement.setObject(8, LocalDateTime.of(2026, 10, 1, 10, 0).plusDays(createDay));
            statement.executeUpdate();
        }
    }

    private static List<Long> ids(PageDTO<UserCouponPageVO> page) {
        return page.getList().stream().map(UserCouponPageVO::getId).collect(Collectors.toList());
    }

    private static class TestUserCouponService extends UserCouponServiceImpl {
        TestUserCouponService(UserCouponMapper userMapper, CouponMapper couponMapper) {
            super(couponMapper, null);
            this.baseMapper = userMapper;
        }
    }
}
