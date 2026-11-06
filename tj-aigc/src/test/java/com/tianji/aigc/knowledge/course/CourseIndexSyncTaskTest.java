package com.tianji.aigc.knowledge.course;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseIndexInfoDTO;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class CourseIndexSyncTaskTest {
    private final CourseClient courses = mock(CourseClient.class);
    private final CourseIndexSyncService sync = mock(CourseIndexSyncService.class);
    private final CourseIndexSyncTask task = new CourseIndexSyncTask(courses, sync, 2);

    private CourseIndexInfoDTO row(long id) {
        var row = new CourseIndexInfoDTO();
        row.setId(id);
        return row;
    }

    @Test
    void advancesByLongIdAndRepeatsFullSweepWithoutOneFailureBlockingOtherCourses() throws IOException {
        long id = 1589888774267072513L;
        when(courses.querySemanticIndexPage(0, 2)).thenReturn(List.of(row(10), row(id)));
        when(courses.querySemanticIndexPage(id, 2)).thenReturn(List.of(row(id + 1)));
        doThrow(new IOException("embedding unavailable")).when(sync).synchronize(10);
        task.reconcile();
        task.reconcile();
        task.reconcile();
        verify(courses, times(2)).querySemanticIndexPage(0, 2);
        verify(sync, times(2)).synchronize(10);
        verify(sync, times(2)).synchronize(id);
        verify(sync).synchronize(id + 1);
    }

    @Test
    void pageLookupFailureKeepsCursorForNextAttempt() {
        when(courses.querySemanticIndexPage(0, 2)).thenThrow(new IllegalStateException("offline"));
        task.reconcile();
        task.reconcile();
        verify(courses, times(2)).querySemanticIndexPage(0, 2);
        assertThat(task.stalledAfterId()).isEqualTo(0L);
        assertThat(task.lastPageCompletedAt()).isNull();
        verifyNoInteractions(sync);
    }

    @Test
    void rejectsUnsortedPageWithoutSynchronizingAnyRows() {
        when(courses.querySemanticIndexPage(0, 2)).thenReturn(List.of(row(20), row(10)));
        task.reconcile();
        assertThat(task.stalledAfterId()).isEqualTo(0L);
        assertThat(task.lastPageCompletedAt()).isNull();
        verifyNoInteractions(sync);
    }

    @Test
    void nonFirstPageFailureKeepsCursorAndLastCompletionThenRecoveryClearsStall() throws IOException {
        when(courses.querySemanticIndexPage(0, 2)).thenReturn(List.of(row(10), row(20)));
        when(courses.querySemanticIndexPage(20, 2)).thenReturn(List.of(row(30), row(25)), List.of(row(30)));
        task.reconcile();
        var completedAt = task.lastPageCompletedAt();
        assertThat(completedAt).isNotNull();
        task.reconcile();
        assertThat(task.stalledAfterId()).isEqualTo(20L);
        assertThat(task.lastPageCompletedAt()).isEqualTo(completedAt);
        verify(sync, never()).synchronize(30);
        verify(sync, never()).synchronize(25);

        task.reconcile();
        verify(courses, times(2)).querySemanticIndexPage(20, 2);
        verify(sync).synchronize(30);
        assertThat(task.stalledAfterId()).isNull();
        assertThat(task.lastPageSyncFailures()).isZero();
    }

    @Test
    void pageCompletionCountsCourseFailuresWithoutReportingPaginationStall() throws IOException {
        when(courses.querySemanticIndexPage(0, 2)).thenReturn(List.of(row(10), row(20)));
        doThrow(new IOException("embedding unavailable")).when(sync).synchronize(anyLong());
        task.reconcile();
        assertThat(task.lastPageCompletedAt()).isNotNull();
        assertThat(task.lastPageSyncFailures()).isEqualTo(2);
        assertThat(task.stalledAfterId()).isNull();
        when(courses.querySemanticIndexPage(20, 2)).thenReturn(List.of());
        task.reconcile();
        assertThat(task.lastPageSyncFailures()).isZero();
    }

    @Test
    void repeatedPageFailureLogsFirstAndTenthAndSuccessfulPageResetsCounter() {
        Logger logger = (Logger) LoggerFactory.getLogger(CourseIndexSyncTask.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(courses.querySemanticIndexPage(0, 2)).thenThrow(new IllegalStateException("offline"));
            for (int i = 0; i < 10; i++) task.reconcile();
            var errors = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(2);
            assertThat(errors.get(0).getFormattedMessage()).contains("连续失败 1 次");
            assertThat(errors.get(1).getFormattedMessage()).contains("连续失败 10 次");

            reset(courses);
            when(courses.querySemanticIndexPage(0, 2)).thenReturn(List.of());
            task.reconcile();
            assertThat(task.stalledAfterId()).isNull();
            when(courses.querySemanticIndexPage(0, 2)).thenThrow(new IllegalStateException("offline again"));
            task.reconcile();
            assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList()).hasSize(3);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void emptyFinalPageResetsCursor() {
        when(courses.querySemanticIndexPage(0, 2)).thenReturn(List.of(row(10), row(20)));
        when(courses.querySemanticIndexPage(20, 2)).thenReturn(List.of());
        task.reconcile();
        task.reconcile();
        task.reconcile();
        verify(courses, times(2)).querySemanticIndexPage(0, 2);
    }
}
