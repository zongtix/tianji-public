package com.tianji.aigc.knowledge.course;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseIndexInfoDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CourseIndexSyncServiceTest {
    private static final long ID = 1589888774267072513L;
    private final CourseClient courses = mock(CourseClient.class);
    private final CourseIndexRepository repository = mock(CourseIndexRepository.class);
    private final EmbeddingModel embedding = mock(EmbeddingModel.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private CourseIndexSyncService service;
    private CourseIndexInfoDTO snapshot;

    @BeforeEach
    void setUp() {
        service = new CourseIndexSyncService(courses, repository, embedding, clock, "text-embedding-v4", 1024);
        snapshot = new CourseIndexInfoDTO();
        snapshot.setId(ID);
        snapshot.setName("Java程序员的前端课程");
        snapshot.setStatus(2);
        snapshot.setDeleted(0);
        snapshot.setIntroduce("后端开发者补前端");
        snapshot.setUsePeople("后端基础");
        snapshot.setDetail("<p>HTML、CSS、JavaScript</p>");
        when(courses.getSemanticIndexInfo(ID)).thenReturn(snapshot);
        float[] vector = new float[1024];
        vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saved() throws IOException {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repository).write(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void createsOnceThenSkipsUnchangedBodyWithoutAnotherEmbedding() throws IOException {
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.UPDATED);
        var document = saved();
        assertThat(document).containsKeys("id", "content", "embedding", "metadata");
        when(repository.find(ID)).thenReturn(new CourseIndexRepository.Stored(document.get("id").toString(), document, 1, 1));
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.UNCHANGED);
        verify(embedding, times(1)).embed(anyString());
        verify(repository, times(1)).write(any(), anyMap());
    }

    @Test
    void updatesTheExistingLegacyDocumentInsteadOfCreatingADuplicate() throws IOException {
        var old = new CourseIndexRepository.Stored("legacy-hash-id", Map.of("metadata", Map.of("documentType", "course", "contentHash", "old")), 8, 2);
        when(repository.find(ID)).thenReturn(old);
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.UPDATED);
        assertThat(saved().get("id")).isEqualTo("legacy-hash-id");
        verify(repository).write(eq(old), anyMap());
    }

    @Test
    void changedCatalogueUpdatesExistingVectorAndRepeatedSnapshotSkipsEmbedding() throws IOException {
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.UPDATED);
        var before = saved();
        when(repository.find(ID)).thenReturn(new CourseIndexRepository.Stored("existing-id", before, 1, 1));
        clearInvocations(repository, embedding);
        snapshot.setCatalogue(List.of("认识微服务", "认识微服务 / 架构演变", "认识微服务 / Spring Cloud"));
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.UPDATED);
        var after = saved();
        assertThat(after.get("id")).isEqualTo("existing-id");
        assertThat(after.get("content").toString()).contains("正式章节目录（仅标题）：", "架构演变", "Spring Cloud");
        assertThat(((Map<?, ?>) after.get("metadata")).get("contentHash"))
                .isNotEqualTo(((Map<?, ?>) before.get("metadata")).get("contentHash"));
        when(repository.find(ID)).thenReturn(new CourseIndexRepository.Stored("existing-id", after, 2, 1));
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.UNCHANGED);
        verify(embedding, times(1)).embed(after.get("content").toString());
        verify(repository, times(1)).write(any(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"down", "deleted", "expired", "future", "missing", "empty"})
    void disablesUnavailableCoursesWithoutCallingEmbedding(String state) throws IOException {
        switch (state) {
            case "down" -> snapshot.setStatus(3);
            case "deleted" -> snapshot.setDeleted(1);
            case "expired" -> snapshot.setPurchaseEndTime(LocalDateTime.now(clock));
            case "future" -> snapshot.setPurchaseStartTime(LocalDateTime.now(clock).plusSeconds(1));
            case "missing" -> when(courses.getSemanticIndexInfo(ID)).thenReturn(null);
            case "empty" -> { snapshot.setIntroduce(""); snapshot.setDetail("<p></p>"); }
        }
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.DISABLED);
        assertThat(saved()).doesNotContainKey("embedding");
        assertThat(((Map<?, ?>) saved().get("metadata")).get("documentType")).isEqualTo("course_unavailable");
        verifyNoInteractions(embedding);
    }

    @Test
    void restoresTheDisabledRecordAtTheSameDocumentId() throws IOException {
        var old = new CourseIndexRepository.Stored("disabled-id", Map.of("metadata", Map.of("documentType", "course_unavailable", "contentHash", "unavailable")), 9, 2);
        when(repository.find(ID)).thenReturn(old);
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.UPDATED);
        assertThat(saved()).containsEntry("id", "disabled-id").containsKey("embedding");
    }

    @Test
    void keepsOldDataWhenCourseLookupFailsAndStopsAfterThreeAttempts() throws IOException {
        when(courses.getSemanticIndexInfo(ID)).thenThrow(new IllegalStateException("course service unavailable"));
        assertThatThrownBy(() -> service.synchronize(ID)).isInstanceOf(IllegalStateException.class);
        verify(courses, times(3)).getSemanticIndexInfo(ID);
        verify(repository, never()).write(any(), anyMap());
        verifyNoInteractions(embedding);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentDisableWinsOverBothAnInFlightCreateAndAnUpdate(boolean wasPresent) throws IOException {
        var prior = new CourseIndexRepository.Stored("legacy-id", Map.of("metadata", Map.of("documentType", "course", "contentHash", "old")), 1, 1);
        var disabled = new CourseIndexRepository.Stored("disabled-id", Map.of("metadata", Map.of("documentType", "course_unavailable", "contentHash", "unavailable")), 2, 1);
        when(repository.find(ID)).thenReturn(wasPresent ? prior : null, disabled);
        var down = new CourseIndexInfoDTO();
        down.setId(ID); down.setDeleted(0); down.setStatus(3);
        when(courses.getSemanticIndexInfo(ID)).thenReturn(snapshot, down);
        doThrow(new IOException("version conflict")).when(repository).write(any(), anyMap());
        assertThat(service.synchronize(ID)).isEqualTo(CourseIndexSyncService.Result.UNCHANGED);
        var ordered = inOrder(repository, courses, embedding);
        ordered.verify(repository).find(ID);
        ordered.verify(courses).getSemanticIndexInfo(ID);
        ordered.verify(embedding).embed(anyString());
        ordered.verify(repository).write(any(), anyMap());
        ordered.verify(repository).find(ID);
        ordered.verify(courses).getSemanticIndexInfo(ID);
        verify(embedding, times(1)).embed(anyString());
        verify(repository, times(1)).write(any(), anyMap());
    }

    @Test
    void rejectsMismatchedSnapshotAndNeverOverwritesTheRequestedCourse() throws IOException {
        snapshot.setId(ID + 1);
        assertThatThrownBy(() -> service.synchronize(ID)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).write(any(), anyMap());
    }

    @Test
    void rejectsInvalidVectorAndPreservesTheIndex() throws IOException {
        when(embedding.embed(anyString())).thenReturn(new float[1024]);
        assertThatThrownBy(() -> service.synchronize(ID)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).write(any(), anyMap());
    }
}
