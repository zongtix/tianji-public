package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.IUserCouponService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UserCouponReceiveTest {
    private AnnotationConfigApplicationContext context;
    private IUserCouponService service;
    private JdbcTemplate jdbc;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
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
                + "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        UserContext.setUser(2L);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        context.close();
    }

    @Test
    void relativeValidityIsCalculatedFromReceiptAndCounterIncreases() {
        coupon(1L);
        service.receiveCoupon(1L);
        UserCoupon record = service.lambdaQuery().eq(UserCoupon::getCouponId, 1L).one();
        assertEquals(2L, record.getUserId());
        assertEquals(UserCouponStatus.UNUSED, record.getStatus());
        assertEquals(record.getTermBeginTime().plusDays(7), record.getTermEndTime());
        assertFalse(record.getTermBeginTime().isBefore(now));
        assertFalse(record.getTermBeginTime().isAfter(LocalDateTime.now()));
        assertEquals(record.getTermBeginTime(), record.getCreateTime());
        assertEquals(1, issued(1L));
    }

    @Test
    void fixedValidityIsCopiedEvenWhenUseStartsLater() {
        coupon(1L);
        jdbc.update("UPDATE coupon SET term_days=0,term_begin_time=?,term_end_time=? WHERE id=1",
                now.plusDays(1), now.plusDays(10));
        service.receiveCoupon(1L);
        UserCoupon record = service.list().get(0);
        assertEquals(now.plusDays(1), record.getTermBeginTime());
        assertEquals(now.plusDays(10), record.getTermEndTime());
    }

    @Test
    void loginIsRequiredAndUnknownCouponIsRejected() {
        UserContext.removeUser();
        assertThrows(UnauthorizedException.class, () -> service.receiveCoupon(1L));
        UserContext.setUser(2L);
        assertThrows(BadRequestException.class, () -> service.receiveCoupon(1L));
        assertEquals(0, service.count());
    }

    @Test
    void nonIssuingStatusesAndExchangeCodeCouponsAreRejected() {
        long id = 1;
        for (int status : new int[]{1, 2, 4, 5}) {
            long currentId = id++;
            coupon(currentId);
            jdbc.update("UPDATE coupon SET status=? WHERE id=?", status, currentId);
            assertThrows(BadRequestException.class, () -> service.receiveCoupon(currentId));
            assertEquals(0, issued(currentId));
        }
        coupon(id);
        jdbc.update("UPDATE coupon SET obtain_way=2 WHERE id=?", id);
        long exchangeCoupon = id;
        assertThrows(BadRequestException.class, () -> service.receiveCoupon(exchangeCoupon));
        assertEquals(0, service.count());
    }

    @Test
    void futureAndExpiredIssueWindowsAreRejectedEvenWithIssuingStatus() {
        coupon(1L);
        coupon(2L);
        jdbc.update("UPDATE coupon SET issue_begin_time=? WHERE id=1", now.plusDays(1));
        jdbc.update("UPDATE coupon SET issue_end_time=? WHERE id=2", now.minusDays(1));
        assertThrows(BadRequestException.class, () -> service.receiveCoupon(1L));
        assertThrows(BadRequestException.class, () -> service.receiveCoupon(2L));
        assertEquals(0, service.count());
    }

    @Test
    void exhaustedStockAndExpiredFixedValidityCannotCreateRecords() {
        coupon(1L);
        coupon(2L);
        jdbc.update("UPDATE coupon SET issue_num=total_num WHERE id=1");
        jdbc.update("UPDATE coupon SET term_days=0,term_begin_time=?,term_end_time=? WHERE id=2",
                now.minusDays(2), now.minusDays(1));
        assertThrows(BadRequestException.class, () -> service.receiveCoupon(1L));
        assertThrows(BadRequestException.class, () -> service.receiveCoupon(2L));
        assertEquals(100, issued(1L));
        assertEquals(0, issued(2L));
        assertEquals(0, service.count());
    }

    @Test
    void usedAndExpiredCouponsConsumeQuotaButOtherUsersHaveSeparateQuota() {
        coupon(1L);
        jdbc.update("UPDATE coupon SET user_limit=2,issue_num=2 WHERE id=1");
        for (int status : new int[]{2, 3}) {
            jdbc.update("INSERT INTO user_coupon (user_id,coupon_id,status,term_end_time) VALUES (2,1,?,?)",
                    status, now.plusDays(7));
        }
        assertThrows(BadRequestException.class, () -> service.receiveCoupon(1L));
        assertEquals(2, issued(1L));
        UserContext.setUser(129L);
        service.receiveCoupon(1L);
        assertEquals(3, issued(1L));
        assertEquals(3, service.count());
    }

    @Test
    void multipleReceiptsAreAllowedOnlyUpToConfiguredLimit() {
        coupon(1L);
        jdbc.update("UPDATE coupon SET user_limit=2 WHERE id=1");
        service.receiveCoupon(1L);
        service.receiveCoupon(1L);
        assertThrows(BadRequestException.class, () -> service.receiveCoupon(1L));
        assertEquals(2, issued(1L));
        assertEquals(2, service.count());
    }

    @Test
    void failedRecordInsertRollsBackIssuedCounter() {
        coupon(1L);
        jdbc.execute("ALTER TABLE user_coupon ADD CONSTRAINT fail_receipt CHECK (coupon_id<>1)");
        assertThrows(RuntimeException.class, () -> service.receiveCoupon(1L));
        assertEquals(0, issued(1L));
        assertEquals(0, service.count());
    }

    @Test
    void concurrentDuplicateRequestsCannotExceedUserLimit() throws Exception {
        coupon(1L);
        assertEquals(1, concurrentReceipts(2L, 2L));
        assertEquals(1, issued(1L));
        assertEquals(1, service.count());
    }

    @Test
    void concurrentDifferentUsersCannotOverIssueLastCoupon() throws Exception {
        coupon(1L);
        jdbc.update("UPDATE coupon SET total_num=1 WHERE id=1");
        assertEquals(1, concurrentReceipts(2L, 129L));
        assertEquals(1, issued(1L));
        assertEquals(1, service.count());
    }

    private int concurrentReceipts(Long firstUser, Long secondUser) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = workers.submit(() -> receive(firstUser, ready, start));
            Future<Integer> second = workers.submit(() -> receive(secondUser, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private int receive(Long userId, CountDownLatch ready, CountDownLatch start) throws Exception {
        UserContext.setUser(userId);
        ready.countDown();
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            service.receiveCoupon(1L);
            return 1;
        } catch (BadRequestException expected) {
            return 0;
        } finally {
            UserContext.removeUser();
        }
    }

    private void coupon(Long id) {
        jdbc.update("INSERT INTO coupon (id,status,obtain_way,issue_begin_time,issue_end_time,"
                        + "term_days,total_num,issue_num,user_limit) VALUES (?,3,1,?,?,7,100,0,1)",
                id, now.minusDays(1), now.plusDays(2));
    }

    private int issued(Long id) {
        return jdbc.queryForObject("SELECT issue_num FROM coupon WHERE id=?", Integer.class, id);
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:coupon_receive_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
            config.getTypeHandlerRegistry().register(CouponStatus.class, MybatisEnumTypeHandler.class);
            config.getTypeHandlerRegistry().register(DiscountType.class, MybatisEnumTypeHandler.class);
            config.getTypeHandlerRegistry().register(ObtainType.class, MybatisEnumTypeHandler.class);
            config.getTypeHandlerRegistry().register(UserCouponStatus.class, MybatisEnumTypeHandler.class);
            config.getTypeHandlerRegistry().register(ExchangeCodeStatus.class, MybatisEnumTypeHandler.class);
            config.addMapper(CouponMapper.class);
            config.addMapper(UserCouponMapper.class);
            config.addMapper(ExchangeCodeMapper.class);
            return new MybatisSqlSessionFactoryBuilder().build(config);
        }

        @Bean
        SqlSessionTemplate sessions(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        CouponMapper couponMapper(SqlSessionTemplate sessions) {
            return sessions.getMapper(CouponMapper.class);
        }

        @Bean
        UserCouponMapper userCouponMapper(SqlSessionTemplate sessions) {
            return sessions.getMapper(UserCouponMapper.class);
        }

        @Bean
        ExchangeCodeMapper exchangeCodeMapper(SqlSessionTemplate sessions) {
            return sessions.getMapper(ExchangeCodeMapper.class);
        }

        @Bean
        IUserCouponService userCouponService(CouponMapper couponMapper, ExchangeCodeMapper exchangeCodeMapper) {
            return new UserCouponServiceImpl(couponMapper, exchangeCodeMapper);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
    }
}
