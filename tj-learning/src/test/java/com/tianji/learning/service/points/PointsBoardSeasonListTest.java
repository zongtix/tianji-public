package com.tianji.learning.service.points;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.tianji.common.autoconfigure.mybatis.MybatisConfig;
import com.tianji.learning.controller.points.PointsBoardSeasonController;
import com.tianji.learning.mapper.points.PointsBoardSeasonMapper;
import com.tianji.learning.mapper.points.PointsBoardMapper;
import com.tianji.learning.service.points.impl.PointsBoardSeasonServiceImpl;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PointsBoardSeasonListTest {
    private SqlSession session;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("season-list", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:season_list_" + UUID.randomUUID()
                        + ";MODE=MySQL", "sa", "")));
        configuration.addInterceptor(new MybatisConfig().mybatisPlusInterceptor(null));
        configuration.addMapper(PointsBoardSeasonMapper.class);
        configuration.addMapper(PointsBoardMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("CREATE TABLE points_board_season12(id BIGINT PRIMARY KEY, user_id BIGINT, points INT)");
            statement.execute("CREATE TABLE points_board_season(id INT PRIMARY KEY, name VARCHAR(30), "
                    + "begin_time DATE, end_time DATE)");
        }
        // 打乱插入顺序；当前赛季恰好今天开始，未来赛季从明天开始。
        LocalDate today = LocalDate.now();
        insertSeason(13, today);
        insertSeason(14, today.plusDays(1));
        insertSeason(11, today.minusMonths(2));
        insertSeason(12, today.minusMonths(1));
        PointsBoardSeasonServiceImpl service = new PointsBoardSeasonServiceImpl(session.getMapper(PointsBoardMapper.class));
        ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(PointsBoardSeasonMapper.class));
        mvc = MockMvcBuilders.standaloneSetup(new PointsBoardSeasonController(service)).build();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void frontendRouteReturnsStartedSeasonsWithCurrentSeasonLastAndOriginalIds() throws Exception {
        mvc.perform(get("/boards/seasons/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(12))
                .andExpect(jsonPath("$[1].id").value(13))
                .andExpect(jsonPath("$[0].name").value("Season 12"))
                .andExpect(jsonPath("$[0].beginTime").exists())
                .andExpect(jsonPath("$[0].endTime").exists());
    }

    @Test
    void noStartedSeasonsReturnsAnEmptyArray() throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("DELETE FROM points_board_season WHERE id <> 14");
        }

        mvc.perform(get("/boards/seasons/list"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    private void insertSeason(int id, LocalDate beginTime) throws Exception {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "INSERT INTO points_board_season VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, "Season " + id);
            statement.setObject(3, beginTime);
            statement.setObject(4, beginTime.plusMonths(1).minusDays(1));
            statement.executeUpdate();
        }
    }
}
