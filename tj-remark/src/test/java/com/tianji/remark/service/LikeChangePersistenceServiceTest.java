package com.tianji.remark.service;

import com.tianji.remark.domain.dto.LikeChange;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

class LikeChangePersistenceServiceTest {
    @Test
    void persistsHighestVersionLikeWhenChangesArriveOutOfOrder() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LikeChangePersistenceService service = new LikeChangePersistenceService(jdbcTemplate, existingTargets());

        List<LikeChange> snapshots = service.persistBatch(List.of(
                change(300L, true), change(100L, false), change(200L, false)));

        verify(jdbcTemplate).update(startsWith("INSERT"), eq(1L), eq(2L), eq("NOTE"));
        verify(jdbcTemplate, never()).update(startsWith("DELETE"), eq(1L), eq(2L), eq("NOTE"));
        assertEquals(300L, snapshots.get(0).getVersion());
        assertEquals(true, snapshots.get(0).getLiked());
        assertEquals(1, snapshots.size());
    }

    @Test
    void persistsHighestVersionUnlikeWhenChangesArriveOutOfOrder() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LikeChangePersistenceService service = new LikeChangePersistenceService(jdbcTemplate, existingTargets());

        List<LikeChange> snapshots = service.persistBatch(List.of(
                change(300L, false), change(100L, true), change(200L, true)));

        verify(jdbcTemplate).update(startsWith("DELETE"), eq(1L), eq(2L), eq("NOTE"));
        verify(jdbcTemplate, never()).update(startsWith("INSERT"), eq(1L), eq(2L), eq("NOTE"));
        assertEquals(300L, snapshots.get(0).getVersion());
        assertEquals(false, snapshots.get(0).getLiked());
        assertEquals(1, snapshots.size());
    }

    @Test
    void sendsManyLikesInOneIdempotentStatement() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LikeChangePersistenceService service = new LikeChangePersistenceService(jdbcTemplate, existingTargets());
        LikeChange first = change(1L, true);
        LikeChange second = change(2L, true);
        second.setUserId(2L);

        service.persistBatch(List.of(first, second));

        verify(jdbcTemplate).update(
                eq("INSERT INTO liked_record (user_id, biz_id, biz_type) " +
                        "VALUES (?, ?, ?), (?, ?, ?) ON DUPLICATE KEY UPDATE id = id"),
                eq(1L), eq(2L), eq("NOTE"), eq(2L), eq(2L), eq("NOTE"));
    }

    @Test
    void sendsManyUnlikesInOneStatement() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LikeChangePersistenceService service = new LikeChangePersistenceService(jdbcTemplate, existingTargets());
        LikeChange first = change(1L, false);
        LikeChange second = change(2L, false);
        second.setUserId(2L);

        service.persistBatch(List.of(first, second));

        verify(jdbcTemplate).update(
                eq("DELETE FROM liked_record WHERE (user_id, biz_id, biz_type) IN " +
                        "((?, ?, ?), (?, ?, ?))"),
                eq(1L), eq(2L), eq("NOTE"), eq(2L), eq(2L), eq("NOTE"));
    }

    private LikeTargetService existingTargets() {
        LikeTargetService targets = mock(LikeTargetService.class);
        when(targets.existingIds(anyString(), any())).thenAnswer(invocation ->
                new java.util.HashSet<>(invocation.getArgument(1, java.util.Collection.class)));
        return targets;
    }

    @Test
    void deletedTargetIsCleanedInsteadOfRecreatingRelationship() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LikeTargetService targets = mock(LikeTargetService.class);
        when(targets.existingIds("QA", List.of(2L))).thenReturn(java.util.Collections.emptySet());
        LikeChange pendingLike = change(300L, true);
        pendingLike.setBizType("QA");
        LikeChangePersistenceService service = new LikeChangePersistenceService(jdbc, targets);

        assertEquals(java.util.Collections.emptyList(), service.persistBatch(List.of(pendingLike)));
        assertEquals(java.util.Collections.emptyList(), service.persistBatch(List.of(pendingLike)));

        verify(targets, org.mockito.Mockito.times(2)).removeMissingTargets("QA", List.of(2L));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void targetLookupFailureDoesNotWriteRelationships() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LikeTargetService targets = mock(LikeTargetService.class);
        when(targets.existingIds(anyString(), any())).thenThrow(
                new com.tianji.remark.exception.LikeServiceUnavailableException("learning down"));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        ProxyFactory proxy = new ProxyFactory(new LikeChangePersistenceService(jdbc, targets));
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        LikeChangePersistenceService service = (LikeChangePersistenceService) proxy.getProxy();

        org.junit.jupiter.api.Assertions.assertThrows(
                com.tianji.remark.exception.LikeServiceUnavailableException.class,
                () -> service.persistBatch(List.of(change(300L, true))));
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    private LikeChange change(long version, boolean liked) {
        LikeChange change = new LikeChange();
        change.setUserId(1L);
        change.setBizType("NOTE");
        change.setBizId(2L);
        change.setLiked(liked);
        change.setLikedTimes(liked ? 1 : 0);
        change.setVersion(version);
        return change;
    }
}
