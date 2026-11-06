package com.tianji.remark.service;

import com.tianji.api.client.learning.LearningClient;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.remark.exception.LikeServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LikeTargetServiceTest {
    @Test
    void missingTargetReturnsBadRequestWithoutChangingRedisOrDatabase() {
        LearningClient client = mock(LearningClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(client.queryExistingReplyIds(List.of(2L))).thenReturn(Set.of());

        assertEquals(400, assertThrows(BadRequestException.class,
                () -> new LikeTargetService(client, redis, jdbc).requireExists("QA", 2L)).getStatus());
        verifyNoInteractions(redis, jdbc);
    }

    @Test
    void lookupFailureReturnsUnavailableWithoutTreatingTargetAsDeleted() {
        LearningClient client = mock(LearningClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(client.queryExistingReplyIds(List.of(2L))).thenThrow(new IllegalStateException("learning down"));

        assertEquals(503, assertThrows(LikeServiceUnavailableException.class,
                () -> new LikeTargetService(client, redis, jdbc).requireExists("QA", 2L)).getStatus());
        verifyNoInteractions(redis, jdbc);
    }

    @Test
    void nullResponseDoesNotPermitWritesOrCleanup() {
        LearningClient client = mock(LearningClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(client.queryExistingReplyIds(List.of(2L))).thenReturn(null);

        assertThrows(LikeServiceUnavailableException.class,
                () -> new LikeTargetService(client, redis, jdbc).requireExists("QA", 2L));
        verifyNoInteractions(redis, jdbc);
    }

    @Test
    void noteKeepsItsReservedOpenContractWithoutDeletingUnverifiedTargets() {
        LearningClient client = mock(LearningClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        LikeTargetService targets = new LikeTargetService(client, redis, jdbc);
        assertDoesNotThrow(() -> targets.requireExists("NOTE", 2L));
        assertEquals(Set.of(2L, 3L), targets.existingIds("NOTE", List.of(2L, 3L)));
        verifyNoInteractions(client, redis, jdbc);
    }

    @Test
    void largeAndDuplicateIdCollectionsAreQueriedInBoundedBatches() {
        LearningClient client = mock(LearningClient.class);
        List<List<Long>> requests = new ArrayList<>();
        when(client.queryExistingReplyIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = new ArrayList<>(invocation.getArgument(0, List.class));
            org.junit.jupiter.api.Assertions.assertTrue(ids.size() <= 100);
            requests.add(ids);
            return new HashSet<>(ids);
        });
        List<Long> ids = java.util.stream.LongStream.range(0, 501)
                .map(i -> Long.MAX_VALUE - i).boxed().collect(java.util.stream.Collectors.toList());
        ids.addAll(new ArrayList<>(ids));
        LikeTargetService targets = new LikeTargetService(client,
                mock(StringRedisTemplate.class), mock(JdbcTemplate.class));

        assertEquals(new HashSet<>(ids), targets.existingIds("QA", ids));
        assertEquals(6, requests.size());
        assertEquals(501, requests.stream().mapToInt(List::size).sum());
    }

    @Test
    void aLaterBatchFailureCannotReturnPartialExistenceResults() {
        LearningClient client = mock(LearningClient.class);
        when(client.queryExistingReplyIds(anyList())).thenReturn(Set.of(2L))
                .thenThrow(new IllegalStateException("second batch failed"));
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed()
                .collect(java.util.stream.Collectors.toList());

        assertThrows(LikeServiceUnavailableException.class, () -> new LikeTargetService(client,
                mock(StringRedisTemplate.class), mock(JdbcTemplate.class)).existingIds("QA", ids));
    }

}
