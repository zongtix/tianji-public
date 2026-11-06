package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponPageVO;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CouponPageQueryTest {
    private static SqlSessionFactory sessionFactory;
    private SqlSession session;
    private CouponServiceImpl service;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:coupon_page_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE coupon (id BIGINT PRIMARY KEY, `name` VARCHAR(100), type INT DEFAULT 1, "
                    + "discount_type INT, `specific` BOOLEAN, discount_value INT, threshold_amount INT, "
                    + "max_discount_amount INT, obtain_way INT, issue_begin_time TIMESTAMP, issue_end_time TIMESTAMP, "
                    + "term_days INT, term_begin_time TIMESTAMP, term_end_time TIMESTAMP, status INT, total_num INT, "
                    + "issue_num INT, used_num INT, user_limit INT, ext_param VARCHAR(100), create_time TIMESTAMP, "
                    + "update_time TIMESTAMP, creater BIGINT, updater BIGINT)");
            statement.execute("INSERT INTO coupon (id, `name`, discount_type, `specific`, discount_value, "
                    + "threshold_amount, max_discount_amount, obtain_way, status, total_num, issue_num, used_num, "
                    + "user_limit, term_days, create_time) VALUES "
                    + "(1, 'Java入门满减', 1, FALSE, 200, 1000, 0, 1, 1, 100, 12, 4, 1, 30, '2026-10-01 10:00:00'), "
                    + "(2, 'Java进阶折扣', 2, FALSE, 80, 0, 500, 1, 3, 100, 12, 4, 1, 30, '2026-10-02 10:00:00'), "
                    + "(3, 'Python无门槛', 3, FALSE, 200, 0, 0, 2, 4, 100, 12, 4, 1, 30, '2026-10-03 10:00:00'), "
                    + "(4, 'Java普通满减', 4, TRUE, 200, 1000, 0, 1, 2, 100, 12, 4, 1, 30, '2026-10-04 10:00:00'), "
                    + "(5, 'Python暂停满减', 1, FALSE, 200, 1000, 0, 1, 5, 100, 12, 4, 1, 30, '2026-10-05 10:00:00'), "
                    + "(6, 'Java高级折扣', 2, FALSE, 80, 0, 500, 1, 3, 100, 12, 4, 1, 30, '2026-10-05 10:00:00')");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeHandlerRegistry().register(DiscountType.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(CouponStatus.class, MybatisEnumTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(ObtainType.class, MybatisEnumTypeHandler.class);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        configuration.addInterceptor(interceptor);
        configuration.addMapper(CouponMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUpService() {
        session = sessionFactory.openSession();
        service = new TestCouponService(session.getMapper(CouponMapper.class));
    }

    @AfterEach
    void closeSession() {
        session.close();
    }

    @Test
    void paginationHasCorrectTotalsAndStableOrder() {
        CouponQuery query = new CouponQuery();
        query.setPageSize(2);
        PageDTO<CouponPageVO> first = service.queryCouponPage(query);
        assertEquals(6L, first.getTotal());
        assertEquals(3L, first.getPages());
        assertEquals(Arrays.asList(6L, 5L), ids(first));
        query.setPageNo(2);
        assertEquals(Arrays.asList(4L, 3L), ids(service.queryCouponPage(query)));
    }

    @Test
    void typeFiltersDiscountTypeInsteadOfGenericCouponType() {
        PageDTO<CouponPageVO> page = service.queryCouponPage(new CouponQuery().setType(2));
        assertEquals(2L, page.getTotal());
        assertEquals(Arrays.asList(6L, 2L), ids(page));
        assertTrue(page.getList().stream().allMatch(coupon -> coupon.getDiscountType() == DiscountType.RATE_DISCOUNT));
    }

    @Test
    void pauseStatusIsQueryable() {
        PageDTO<CouponPageVO> page = service.queryCouponPage(new CouponQuery().setStatus(5));
        assertEquals(Arrays.asList(5L), ids(page));
        assertEquals(CouponStatus.PAUSE, page.getList().get(0).getStatus());
    }

    @Test
    void nameAndTypeAndStatusAreCombined() {
        CouponQuery query = new CouponQuery().setName(" Java ").setType(2).setStatus(3);
        assertEquals(Arrays.asList(6L, 2L), ids(service.queryCouponPage(query)));
        query.setStatus(1);
        PageDTO<CouponPageVO> empty = service.queryCouponPage(query);
        assertEquals(0L, empty.getTotal());
        assertEquals(0L, empty.getPages());
        assertTrue(empty.getList().isEmpty());
    }

    @Test
    void blankNameDoesNotFilterResults() {
        assertEquals(6L, service.queryCouponPage(new CouponQuery().setName("   ")).getTotal());
    }

    @Test
    void pageBeyondLastRetainsTotalsAndReturnsEmptyList() {
        CouponQuery query = new CouponQuery();
        query.setPageNo(10);
        query.setPageSize(2);
        PageDTO<CouponPageVO> page = service.queryCouponPage(query);
        assertEquals(6L, page.getTotal());
        assertEquals(3L, page.getPages());
        assertTrue(page.getList().isEmpty());
    }

    @Test
    void inheritedSortInputCannotChangeFixedOrder() {
        CouponQuery query = new CouponQuery();
        query.setSortBy("id desc; DROP TABLE coupon");
        query.setIsAsc(true);
        assertEquals(Arrays.asList(6L, 5L, 4L, 3L, 2L, 1L), ids(service.queryCouponPage(query)));
    }

    @Test
    void pageVoContainsCouponRulesAndCounters() {
        CouponPageVO coupon = service.queryCouponPage(new CouponQuery()).getList().get(0);
        assertEquals("Java高级折扣", coupon.getName());
        assertEquals(Boolean.FALSE, coupon.getSpecific());
        assertEquals(80, coupon.getDiscountValue());
        assertEquals(0, coupon.getThresholdAmount());
        assertEquals(500, coupon.getMaxDiscountAmount());
        assertEquals(ObtainType.PUBLIC, coupon.getObtainWay());
        assertEquals(100, coupon.getTotalNum());
        assertEquals(12, coupon.getIssueNum());
        assertEquals(4, coupon.getUsedNum());
        assertEquals(30, coupon.getTermDays());
        assertNotNull(coupon.getCreateTime());
    }

    private static class TestCouponService extends CouponServiceImpl {
        TestCouponService(CouponMapper mapper) {
            super(null, null, null);
            this.baseMapper = mapper;
        }
    }

    private static List<Long> ids(PageDTO<CouponPageVO> page) {
        return page.getList().stream().map(CouponPageVO::getId).collect(Collectors.toList());
    }
}
