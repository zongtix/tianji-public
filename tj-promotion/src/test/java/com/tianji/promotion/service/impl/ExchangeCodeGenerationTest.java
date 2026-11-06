package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.promotion.config.PromotionConfig;
import com.tianji.promotion.domain.dto.CouponIssueDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.utils.CodeUtil;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExchangeCodeGenerationTest {
    private static final String SERIAL_KEY = "coupon:code:serial";

    private AnnotationConfigApplicationContext context;
    private ICouponService couponService;
    private IExchangeCodeService codeService;
    private ExchangeCodeMapper codeMapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ValueOperations<String, String> serialOps;
    private BlockingQueue<Throwable> failures;
    private AtomicLong serial;
    private LocalDateTime end;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        couponService = context.getBean(ICouponService.class);
        codeService = context.getBean(IExchangeCodeService.class);
        codeMapper = context.getBean(ExchangeCodeMapper.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        failures = context.getBean(BlockingQueue.class);
        serialOps = mock(ValueOperations.class);
        serial = new AtomicLong();
        when(context.getBean(StringRedisTemplate.class).opsForValue()).thenReturn(serialOps);
        when(serialOps.increment(eq(SERIAL_KEY), anyLong()))
                .thenAnswer(invocation -> serial.addAndGet(invocation.getArgument(1)));
        end = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusDays(3);
        jdbc.execute("CREATE TABLE coupon (id BIGINT PRIMARY KEY, `name` VARCHAR(100), type INT, "
                + "discount_type INT, `specific` BOOLEAN, discount_value INT, threshold_amount INT, "
                + "max_discount_amount INT, obtain_way INT, issue_begin_time TIMESTAMP, issue_end_time TIMESTAMP, "
                + "term_days INT, term_begin_time TIMESTAMP, term_end_time TIMESTAMP, status INT, total_num INT, "
                + "issue_num INT, used_num INT, user_limit INT, ext_param VARCHAR(100), create_time TIMESTAMP, "
                + "update_time TIMESTAMP, creater BIGINT, updater BIGINT)");
        jdbc.execute("CREATE TABLE exchange_code (id INT PRIMARY KEY, code VARCHAR(10) UNIQUE, status INT, "
                + "user_id BIGINT, type INT, exchange_target_id BIGINT, create_time TIMESTAMP, "
                + "expired_time TIMESTAMP, update_time TIMESTAMP)");
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void generatesAfterCommitOnWorkerWithNewIssueEndAndBatchRemainder() throws Exception {
        draft(17, ObtainType.ISSUE, 2501);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> worker = new AtomicReference<>();
        when(serialOps.increment(SERIAL_KEY, 2501L)).thenAnswer(invocation -> {
            worker.set(Thread.currentThread().getName());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return serial.addAndGet(2501);
        });
        try {
            transaction.execute(status -> {
                couponService.issueCoupon(17L, issue());
                verifyNoInteractions(serialOps);
                assertEquals(0, codeMapper.selectCount(null));
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(worker.get().startsWith("exchange-code-handler-"));
            assertEquals(CouponStatus.ISSUING, couponService.getById(17L).getStatus());
            assertEquals(0, codeMapper.selectCount(null));
        } finally {
            release.countDown();
        }
        awaitCodes(2501);
        verify(serialOps, times(1)).increment(SERIAL_KEY, 2501L);
        for (ExchangeCode code : codeMapper.selectList(null)) {
            assertEquals(code.getId().longValue(), CodeUtil.parseCode(code.getCode()));
            assertEquals(17L, code.getExchangeTargetId());
            assertEquals(ExchangeCodeStatus.UNUSED, code.getStatus());
            assertEquals(1, code.getType());
            assertEquals(end, code.getExpiredTime());
            assertNotNull(code.getCreateTime());
            assertNotNull(code.getUpdateTime());
            assertNull(code.getUserId());
        }
        assertEquals(0, couponService.getById(17L).getIssueNum());
        assertTrue(failures.isEmpty());
    }

    @Test
    void rollbackDoesNotSubmitGeneration() {
        draft(1, ObtainType.ISSUE, 3);
        transaction.execute(status -> {
            couponService.issueCoupon(1L, issue());
            status.setRollbackOnly();
            return null;
        });
        assertEquals(CouponStatus.DRAFT, couponService.getById(1L).getStatus());
        verifyNoInteractions(serialOps);
        assertEquals(0, executor().getThreadPoolExecutor().getTaskCount());
        assertEquals(0, codeMapper.selectCount(null));
    }

    @Test
    void publicCouponDoesNotGenerateCodes() {
        draft(1, ObtainType.PUBLIC, 3);
        couponService.issueCoupon(1L, issue());
        assertEquals(CouponStatus.ISSUING, couponService.getById(1L).getStatus());
        verifyNoInteractions(serialOps);
        assertEquals(0, executor().getThreadPoolExecutor().getTaskCount());
    }

    @Test
    void scheduledIssueGeneratesNowAndRepeatedIssueCannotGenerateAgain() {
        draft(1, ObtainType.ISSUE, 3);
        CouponIssueDTO dto = issue();
        dto.setIssueBeginTime(end.minusDays(1));
        couponService.issueCoupon(1L, dto);
        awaitCodes(3);
        assertEquals(CouponStatus.UN_ISSUE, couponService.getById(1L).getStatus());
        assertThrows(BadRequestException.class, () -> couponService.issueCoupon(1L, issue()));
        verify(serialOps, times(1)).increment(SERIAL_KEY, 3L);
        assertEquals(3, codeMapper.selectCount(null));
    }

    @Test
    void concurrentJobsForDifferentCouponsReserveDistinctRangesBeyondOneBillion() {
        serial.set(999_999_999L);
        draft(1, ObtainType.ISSUE, 3);
        draft(17, ObtainType.ISSUE, 3);
        couponService.issueCoupon(1L, issue());
        couponService.issueCoupon(17L, issue());
        awaitCodes(6);
        assertEquals(1_000_000_005L, serial.get());
        List<ExchangeCode> codes = codeMapper.selectList(null);
        assertEquals(6, codes.stream().map(ExchangeCode::getId).distinct().count());
        assertEquals(6, codes.stream().map(ExchangeCode::getCode).distinct().count());
        for (ExchangeCode code : codes) {
            assertEquals(code.getId().longValue(), CodeUtil.parseCode(code.getCode()));
            assertTrue(code.getId() >= 1_000_000_000);
        }
        assertTrue(failures.isEmpty());
    }

    @Test
    void integerUpperBoundaryDoesNotWrapTheGenerationLoop() {
        serial.set(Integer.MAX_VALUE - 2L);
        codeService.asyncGenerateCode(snapshot(1, 2));
        awaitCodes(2);
        assertNotNull(codeMapper.selectById(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, CodeUtil.parseCode(codeMapper.selectById(Integer.MAX_VALUE).getCode()));
        assertTrue(failures.isEmpty());
    }

    @Test
    void invalidOrExhaustedRedisRangeDoesNotWriteCodes() throws Exception {
        for (Long max : new Long[]{null, -1L, 1L, Integer.MAX_VALUE + 1L}) {
            when(serialOps.increment(SERIAL_KEY, 2L)).thenReturn(max);
            codeService.asyncGenerateCode(snapshot(1, 2));
            Throwable failure = failures.poll(5, TimeUnit.SECONDS);
            assertInstanceOf(IllegalStateException.class, failure);
            assertEquals(0, codeMapper.selectCount(null));
        }
    }

    @Test
    void laterBatchFailureRollsBackEarlierBatchWithoutRewindingSerial() throws Exception {
        jdbc.execute("ALTER TABLE exchange_code ADD CONSTRAINT fail_later_batch "
                + "CHECK (exchange_target_id <> 99 OR id <= 1000)");
        codeService.asyncGenerateCode(snapshot(99, 1001));
        assertNotNull(failures.poll(5, TimeUnit.SECONDS));
        assertEquals(0, codeMapper.selectCount(null));
        assertEquals(1001, serial.get());
    }

    @Test
    void callerRunsAfterCommitStillPersistsInAnIndependentTransaction() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            // 占满5个工作线程和200个队列位置，下一任务由发放请求线程执行。
            for (int i = 0; i < 205; i++) {
                executor().execute(() -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            draft(1, ObtainType.ISSUE, 3);
            AtomicReference<String> worker = new AtomicReference<>();
            when(serialOps.increment(SERIAL_KEY, 3L)).thenAnswer(invocation -> {
                worker.set(Thread.currentThread().getName());
                return serial.addAndGet(3);
            });
            couponService.issueCoupon(1L, issue());
            assertEquals(Thread.currentThread().getName(), worker.get());
            assertEquals(3, codeMapper.selectCount(null));
            assertTrue(failures.isEmpty());
        } finally {
            release.countDown();
        }
    }

    private void awaitCodes(int count) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (codeMapper.selectCount(null) != count) {
                assertTrue(failures.isEmpty(), () -> "异步生成失败: " + failures.peek());
                TimeUnit.MILLISECONDS.sleep(10);
            }
        });
    }

    private ThreadPoolTaskExecutor executor() {
        return context.getBean("generateExchangeCodeExecutor", ThreadPoolTaskExecutor.class);
    }

    private void draft(long id, ObtainType obtainWay, int total) {
        jdbc.update("INSERT INTO coupon (id, `name`, status, obtain_way, total_num, issue_num, used_num, user_limit) "
                + "VALUES (?, '兑换码测试', ?, ?, ?, 0, 0, 1)", id, CouponStatus.DRAFT.getValue(), obtainWay.getValue(), total);
    }

    private CouponIssueDTO issue() {
        CouponIssueDTO dto = new CouponIssueDTO();
        dto.setIssueEndTime(end);
        dto.setTermDays(30);
        return dto;
    }

    private Coupon snapshot(long id, int total) {
        return new Coupon().setId(id).setTotalNum(total).setIssueEndTime(end);
    }

    @Configuration
    @EnableTransactionManagement
    @Import(PromotionConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:exchange_generation_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
            configuration.getTypeHandlerRegistry().register(DiscountType.class, MybatisEnumTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(CouponStatus.class, MybatisEnumTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(ObtainType.class, MybatisEnumTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(ExchangeCodeStatus.class, MybatisEnumTypeHandler.class);
            configuration.addMapper(CouponMapper.class);
            configuration.addMapper(ExchangeCodeMapper.class);
            return new MybatisSqlSessionFactoryBuilder().build(configuration);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        CouponMapper couponMapper(SqlSessionTemplate sessions) {
            return sessions.getMapper(CouponMapper.class);
        }

        @Bean
        ExchangeCodeMapper exchangeCodeMapper(SqlSessionTemplate sessions) {
            return sessions.getMapper(ExchangeCodeMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        StringRedisTemplate redisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        IExchangeCodeService exchangeCodeService(StringRedisTemplate redisTemplate) {
            return new ExchangeCodeServiceImpl(redisTemplate);
        }

        @Bean
        ICouponService couponService(IExchangeCodeService exchangeCodeService) {
            return new CouponServiceImpl(mock(ICouponScopeService.class), exchangeCodeService, null);
        }

        @Bean
        BlockingQueue<Throwable> failures() {
            return new LinkedBlockingQueue<>();
        }

        @Bean
        AsyncConfigurer asyncConfigurer(BlockingQueue<Throwable> failures) {
            return new AsyncConfigurer() {
                @Override
                public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                    return (failure, method, params) -> failures.add(failure);
                }
            };
        }
    }
}
