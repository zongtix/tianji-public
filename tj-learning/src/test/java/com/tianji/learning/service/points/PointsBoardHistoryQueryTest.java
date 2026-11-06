package com.tianji.learning.service.points;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.autoconfigure.mybatis.MybatisConfig;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.points.PointsBoardMapper;
import com.tianji.learning.service.points.impl.PointsBoardServiceImpl;
import com.tianji.learning.utils.TableInfoContext;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations;
import com.tianji.common.exceptions.BizIllegalException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PointsBoardHistoryQueryTest {
    private SqlSession session;
    private PointsBoardServiceImpl service;
    private StringRedisTemplate redisTemplate;
    private UserClient userClient;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoContext.removeInfo();
        UserContext.setUser(103L);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("history-query", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:board_history_" + UUID.randomUUID()
                        + ";MODE=MySQL", "sa", "")));
        configuration.addInterceptor(new MybatisConfig().mybatisPlusInterceptor(
                new com.tianji.learning.config.MybatisConfiguration().dynamicTableNameInnerInterceptor()));
        configuration.addMapper(PointsBoardMapper.class);
        try (java.io.InputStream xml = getClass().getResourceAsStream("/mapper/PointsBoardMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mapper/PointsBoardMapper.xml", configuration.getSqlFragments()).parse();
        }
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        try (Statement statement = session.getConnection().createStatement()) {
            // 与实际历史表一致，不包含 rank、season 列；默认表放入不同数据以验证选表。
            for (String table : List.of("points_board", "points_board_season12", "points_board_season13", "points_board_season14")) {
                statement.execute("CREATE TABLE " + table + "(id BIGINT PRIMARY KEY, user_id BIGINT, points INT)");
            }
            statement.execute("INSERT INTO points_board VALUES(99, 103, 999)");
            statement.execute("INSERT INTO points_board_season12 VALUES(1, 101, 100), (2, 102, 90), "
                    + "(3, 103, 90), (4, 104, 70), (5, 105, 60), (6, 106, 50)");
            statement.execute("INSERT INTO points_board_season13 VALUES(1, 103, 200)");
        }
        redisTemplate = mock(StringRedisTemplate.class);
        userClient = mock(UserClient.class);
        when(userClient.queryUserByIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> {
                UserDTO user = new UserDTO();
                user.setId(id);
                user.setName("student-" + id);
                return user;
            }).collect(Collectors.toList());
        });
        service = new PointsBoardServiceImpl(redisTemplate, userClient);
        ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(PointsBoardMapper.class));
        // MyBatis-Plus 3.5 derives this from the real mapper proxy; these tests inject a mock.
        ReflectionTestUtils.setField(service, "entityClass", PointsBoard.class);
    }

    @AfterEach
    void tearDown() {
        TableInfoContext.removeInfo();
        UserContext.removeUser();
        if (session != null) {
            session.close();
        }
    }

    @Test
    void historicalQueryReturnsPersonalRankAndTheRequestedPageWithNames() {
        PointsBoardQuery query = query(12L, 2, 2);
        query.setSortBy("points");
        query.setIsAsc(false);

        PointsBoardVO result = service.queryPointsBoardBySeason(query);

        assertEquals(3, result.getRank());
        assertEquals(90, result.getPoints());
        assertEquals(List.of(3, 4), result.getBoardList().stream().map(item -> item.getRank()).collect(Collectors.toList()));
        assertEquals(List.of(90, 70), result.getBoardList().stream().map(item -> item.getPoints()).collect(Collectors.toList()));
        assertEquals(List.of("student-103", "student-104"), result.getBoardList().stream().map(item -> item.getName()).collect(Collectors.toList()));
        assertNull(TableInfoContext.getInfo());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void aUserOutsideTheHistoricalBoardHasZeroPointsAndRank() {
        UserContext.setUser(999L);

        PointsBoardVO result = service.queryPointsBoardBySeason(query(12L, 1, 2));

        assertEquals(0, result.getPoints());
        assertEquals(0, result.getRank());
        assertEquals(2, result.getBoardList().size());
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void aPageBeyondTheLastRecordKeepsPersonalRankingAndSkipsUserLookup() {
        PointsBoardVO result = service.queryPointsBoardBySeason(query(12L, 4, 2));

        assertEquals(3, result.getRank());
        assertEquals(90, result.getPoints());
        assertTrue(result.getBoardList() == null || result.getBoardList().isEmpty());
        verifyNoInteractions(userClient);
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void sequentialQueriesUseTheirOwnSeasonTables() {
        PointsBoardVO first = service.queryPointsBoardBySeason(query(12L, 1, 2));
        PointsBoardVO second = service.queryPointsBoardBySeason(query(13L, 1, 2));

        assertEquals(3, first.getRank());
        assertEquals(1, second.getRank());
        assertEquals(200, second.getPoints());
        assertEquals(1, second.getBoardList().size());
        assertEquals(1, second.getBoardList().get(0).getRank());
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void anEmptyHistoricalTableReturnsZeroPersonalRankingAndNoList() {
        PointsBoardVO result = service.queryPointsBoardBySeason(query(14L, 1, 2));

        assertEquals(0, result.getRank());
        assertEquals(0, result.getPoints());
        assertTrue(result.getBoardList() == null || result.getBoardList().isEmpty());
        verifyNoInteractions(userClient);
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void personalQueryFailureClearsTheTableContextAndPropagates() {
        PointsBoardMapper mapper = mock(PointsBoardMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.historyTableExists(any())).thenReturn(1);
        RuntimeException failure = new IllegalStateException("Personal query failed");
        when(mapper.selectOne(any())).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.queryPointsBoardBySeason(query(12L, 1, 2))));

        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void pageQueryFailureClearsTheTableContextAndPropagates() {
        PointsBoardMapper mapper = mock(PointsBoardMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.historyTableExists(any())).thenReturn(1);
        when(mapper.selectOne(any())).thenReturn(new PointsBoard().setId(3L).setUserId(103L).setPoints(90));
        RuntimeException failure = new IllegalStateException("Page query failed");
        when(mapper.selectPage(any(Page.class), any())).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.queryPointsBoardBySeason(query(12L, 1, 2))));

        assertNull(TableInfoContext.getInfo());
    }

    private PointsBoardQuery query(Long season, int pageNo, int pageSize) {
        PointsBoardQuery query = new PointsBoardQuery();
        query.setSeason(season);
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        return query;
    }

    @Test
    void missingHistoricalTableReturnsAnEmptyBoardInsteadOfSqlFailure() {
        PointsBoardVO result = service.queryPointsBoardBySeason(query(11L, 1, 5));
        assertEquals(0, result.getPoints());
        assertEquals(0, result.getRank());
        assertTrue(result.getBoardList().isEmpty());
        verifyNoInteractions(userClient, redisTemplate);
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void partialArchiveCanBeReplayedAndCompletedWithoutDuplicateKeys() throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("DELETE FROM points_board_season12");
        }
        List<PointsBoard> rows = java.util.stream.LongStream.rangeClosed(1, 10)
                .mapToObj(id -> new PointsBoard().setId(id).setUserId(100L + id).setPoints(100 - (int) id))
                .collect(Collectors.toList());
        TableInfoContext.setInfo("points_board_season12");
        service.saveHistoryBatch(rows.subList(0, 5));
        service.saveHistoryBatch(rows.subList(0, 5));
        service.saveHistoryBatch(rows);
        assertEquals(10, service.count());
        assertEquals(90, service.getBaseMapper().queryHistoryRange(10, 10).get(0).getPoints());
        TableInfoContext.removeInfo();
        assertEquals(1, service.queryPointsBoardBySeason(query(13L, 1, 5)).getBoardList().size());
    }

    @SuppressWarnings("unchecked")
    private void mockCompleteRedisBoard() {
        ZSetOperations<String, String> ops = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(ops);
        when(ops.zCard("board:test")).thenReturn(6L);
        Set<ZSetOperations.TypedTuple<String>> rows = new LinkedHashSet<>();
        int[] points = {100, 90, 90, 70, 60, 50};
        for (int i = 0; i < points.length; i++) {
            rows.add(new DefaultTypedTuple<>(String.valueOf(101 + i), (double) points[i]));
        }
        when(ops.reverseRangeWithScores("board:test", 0, 199)).thenReturn(rows);
    }

    @Test
    void completeArchivePassesFullComparisonAndClearsTableContext() {
        mockCompleteRedisBoard();
        service.verifyHistoryBoard(12, "board:test");
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void missingRowsPreventArchiveCleanup() throws Exception {
        mockCompleteRedisBoard();
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("DELETE FROM points_board_season12 WHERE id=6");
        }
        assertThrows(BizIllegalException.class, () -> service.verifyHistoryBoard(12, "board:test"));
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void equalRowCountWithWrongScoresStillPreventsCleanup() throws Exception {
        mockCompleteRedisBoard();
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("UPDATE points_board_season12 SET points=1 WHERE id=3");
        }
        assertThrows(BizIllegalException.class, () -> service.verifyHistoryBoard(12, "board:test"));
        assertNull(TableInfoContext.getInfo());
    }
}
