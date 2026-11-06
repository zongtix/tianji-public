package com.tianji.learning.config;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.common.autoconfigure.mybatis.MybatisConfig;
import com.tianji.learning.utils.TableInfoContext;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DynamicTableRoutingTest {
    private SqlSession session;
    private BoardMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoContext.removeInfo();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:board_routing_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addInterceptor(new MybatisConfig().mybatisPlusInterceptor(
                new MybatisConfiguration().dynamicTableNameInnerInterceptor()));
        configuration.addMapper(BoardMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(BoardMapper.class);
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("CREATE TABLE another_board(id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO another_board VALUES(42)");
            statement.execute("CREATE TABLE points_board(id BIGINT PRIMARY KEY, points INT)");
            statement.execute("CREATE TABLE points_board_season12(id BIGINT PRIMARY KEY, points INT)");
            statement.execute("INSERT INTO points_board_season12 VALUES(1, 99)");
        }
    }

    @AfterEach
    void tearDown() {
        TableInfoContext.removeInfo();
        if (session != null) {
            session.close();
        }
    }

    @Test
    void paginationCountsAndReadsTheHistoricalTableWhenTheOriginalTableIsEmpty() {
        TableInfoContext.setInfo("points_board_season12");
        Page<Long> page = new Page<>(1, 10);

        assertEquals(List.of(1L), mapper.selectIds(page));
        assertEquals(1L, page.getTotal());
    }

    @Test
    void missingTableContextKeepsTheOriginalTable() throws Exception {
        insertOriginalBoard();
        Page<Long> page = new Page<>(1, 10);

        assertEquals(List.of(9L), mapper.selectIds(page));
        assertEquals(1L, page.getTotal());
    }

    @Test
    void blankTableContextKeepsTheOriginalTable() throws Exception {
        insertOriginalBoard();
        for (String tableName : List.of("", " \t")) {
            TableInfoContext.setInfo(tableName);
            Page<Long> page = new Page<>(1, 10);
            assertEquals(List.of(9L), mapper.selectIds(page));
            assertEquals(1L, page.getTotal());
        }
    }

    @Test
    void commonConfigurationStartsWithoutADynamicTableInterceptor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MybatisConfig.class)) {
            assertNotNull(context.getBean(MybatisPlusInterceptor.class));
        }
    }

    @Test
    void historicalContextDoesNotRewriteOtherTables() {
        TableInfoContext.setInfo("points_board_season12");
        Page<Long> page = new Page<>(1, 10);

        assertEquals(List.of(42L), mapper.selectOtherIds(page));
        assertEquals(1L, page.getTotal());
    }

    private void insertOriginalBoard() throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("INSERT INTO points_board VALUES(9, 50)");
        }
    }

    public interface BoardMapper {
        @Select("SELECT id FROM another_board ORDER BY id")
        List<Long> selectOtherIds(@Param("page") IPage<?> page);

        @Select("SELECT id FROM points_board ORDER BY id")
        List<Long> selectIds(@Param("page") IPage<?> page);
    }
}
