package com.tianji.learning.task;

import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.mapper.points.PointsMessageMapper;
import com.tianji.learning.service.points.IPointsBoardSeasonService;
import com.tianji.learning.service.points.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PointBoardPersistentTaskTest {
    private IPointsBoardService boardService;
    private IPointsBoardSeasonService seasonService;
    private StringRedisTemplate redisTemplate;
    private PointBoardPersistentTask task;
    private PointsMessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        TableInfoContext.removeInfo();
        setShardContext(0, 1);
        boardService = mock(IPointsBoardService.class);
        seasonService = mock(IPointsBoardSeasonService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        messageMapper = mock(PointsMessageMapper.class);
        task = new PointBoardPersistentTask(boardService, seasonService, redisTemplate, messageMapper);
    }

    @AfterEach
    void tearDown() {
        TableInfoContext.removeInfo();
        XxlJobContext.setXxlJobContext(null);
    }

    @Test
    void missingSeasonSkipsReadingAndSavingTheBoard() {
        when(seasonService.querySeasonByTime(any())).thenReturn(null);
        task.savePointsBoardToDBJob();

        verifyNoInteractions(boardService, redisTemplate);
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void successfulPersistenceUsesTheSeasonTableAndClearsTheContext() {
        when(seasonService.querySeasonByTime(any())).thenReturn(12);
        PointsBoard board = new PointsBoard().setUserId(101L).setPoints(99).setRank(1);
        when(boardService.queryCurrentBoardList(anyString(), eq(1), anyInt()))
                .thenReturn(List.of(board));
        when(boardService.queryCurrentBoardList(anyString(), eq(2), anyInt()))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            assertEquals("points_board_season12", TableInfoContext.getInfo());
            assertEquals(1L, board.getId());
            assertNull(board.getRank());
            return null;
        }).when(boardService).saveHistoryBatch(anyList());

        task.savePointsBoardToDBJob();

        verify(boardService).saveHistoryBatch(List.of(board));
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void readFailureClearsTheContextAndPropagatesTheException() {
        when(seasonService.querySeasonByTime(any())).thenReturn(12);
        RuntimeException failure = new IllegalStateException("Redis unavailable");
        when(boardService.queryCurrentBoardList(anyString(), anyInt(), anyInt()))
                .thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, task::savePointsBoardToDBJob));

        verify(boardService, never()).saveHistoryBatch(anyList());
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void saveFailureClearsTheContextAndPropagatesTheException() {
        when(seasonService.querySeasonByTime(any())).thenReturn(12);
        when(boardService.queryCurrentBoardList(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(new PointsBoard().setUserId(101L).setPoints(99).setRank(1)));
        RuntimeException failure = new IllegalStateException("Database unavailable");
        doThrow(failure).when(boardService).saveHistoryBatch(anyList());

        assertSame(failure, assertThrows(RuntimeException.class, task::savePointsBoardToDBJob));

        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void emptyBoardSkipsSavingAndClearsTheContext() {
        when(seasonService.querySeasonByTime(any())).thenReturn(12);
        when(boardService.queryCurrentBoardList(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        task.savePointsBoardToDBJob();

        verify(boardService, never()).saveHistoryBatch(anyList());
        assertNull(TableInfoContext.getInfo());
    }

    @Test
    void threeShardsPersistEveryRankOnceWithoutOverlap() {
        when(seasonService.querySeasonByTime(any())).thenReturn(12);
        List<PointsBoard> boards = new ArrayList<>();
        for (int rank = 1; rank <= 20; rank++) {
            boards.add(new PointsBoard().setUserId(100L + rank)
                    .setPoints(210 - rank * 10).setRank(rank));
        }
        List<List<Integer>> requestedPages = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<List<Long>> savedRanksByShard = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        Set<Long> savedRanks = new HashSet<>();
        when(boardService.queryCurrentBoardList(anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            assertEquals("points_board_season12", TableInfoContext.getInfo());
            int pageNo = invocation.getArgument(1);
            int pageSize = invocation.getArgument(2);
            requestedPages.get(XxlJobContext.getXxlJobContext().getShardIndex()).add(pageNo);
            int from = (pageNo - 1) * pageSize;
            return from >= boards.size() ? List.of() : boards.subList(from, Math.min(from + pageSize, boards.size()));
        });
        doAnswer(invocation -> {
            Collection<PointsBoard> batch = invocation.getArgument(0);
            int shardIndex = XxlJobContext.getXxlJobContext().getShardIndex();
            for (PointsBoard board : batch) {
                assertNull(board.getRank());
                assertEquals(board.getUserId() - 100L, board.getId().longValue());
                assertTrue(savedRanks.add(board.getId()), "A rank must only be persisted once");
                savedRanksByShard.get(shardIndex).add(board.getId());
            }
            return null;
        }).when(boardService).saveHistoryBatch(anyList());

        for (int shardIndex = 0; shardIndex < 3; shardIndex++) {
            setShardContext(shardIndex, 3);
            task.savePointsBoardToDBJob();
            assertNull(TableInfoContext.getInfo());
        }

        assertEquals(List.of(1, 4, 7), requestedPages.get(0));
        assertEquals(List.of(2, 5), requestedPages.get(1));
        assertEquals(List.of(3, 6), requestedPages.get(2));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 16L, 17L, 18L, 19L, 20L), savedRanksByShard.get(0));
        assertEquals(List.of(6L, 7L, 8L, 9L, 10L), savedRanksByShard.get(1));
        assertEquals(List.of(11L, 12L, 13L, 14L, 15L), savedRanksByShard.get(2));
        assertEquals(20, savedRanks.size());
    }

    private void setShardContext(int shardIndex, int shardTotal) {
        XxlJobContext.setXxlJobContext(new XxlJobContext(1L, "", null, shardIndex, shardTotal));
    }

    @Test
    void cleanupRefusesIncompleteArchivesAndNeverDeletesTheSource() {
        when(seasonService.querySeasonByTime(any())).thenReturn(12);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        doThrow(new IllegalStateException("incomplete")).when(boardService).verifyHistoryBoard(eq(12), anyString());
        assertThrows(IllegalStateException.class, task::clearPointsBoardFromRedis);
        verify(redisTemplate, never()).unlink(anyString());
    }

    @Test
    void cleanupWaitsForPendingBoardCompensation() {
        when(seasonService.querySeasonByTime(any())).thenReturn(12);
        when(messageMapper.countPendingBoardSync(anyString())).thenReturn(1L);
        assertThrows(com.tianji.common.exceptions.BizIllegalException.class, task::clearPointsBoardFromRedis);
        verifyNoInteractions(redisTemplate, boardService);
    }

    @Test
    void cleanupDeletesOnlyAfterSuccessfulVerificationAndIsRepeatable() {
        when(seasonService.querySeasonByTime(any())).thenReturn(12);
        when(redisTemplate.hasKey(anyString())).thenReturn(true, false);
        task.clearPointsBoardFromRedis();
        task.clearPointsBoardFromRedis();
        org.mockito.InOrder order = inOrder(boardService, redisTemplate);
        order.verify(boardService).verifyHistoryBoard(eq(12), anyString());
        order.verify(redisTemplate).unlink(anyString());
        verify(redisTemplate, times(1)).unlink(anyString());
    }

}
