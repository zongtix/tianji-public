package com.tianji.aigc.tools;

import com.tianji.aigc.knowledge.course.CourseSemanticSearch;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CourseSemanticToolsTest {
    private final VectorStore vectors = mock(VectorStore.class);
    private final CourseClient courses = mock(CourseClient.class);
    private final SearchClient keywords = mock(SearchClient.class);
    private final CourseTools tools = new CourseTools(courses, keywords, new CourseSemanticSearch(vectors));
    private final CourseRecommendations recommendations = new CourseRecommendations();
    private final ToolContext context = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, recommendations));
    private static final long ID = 910000000000010003L;

    @Test
    void semanticCandidatesUseCurrentBusinessDetailsAndOnlySelectedCardsAreShown() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit(ID)));
        var course = course(ID);
        when(courses.getSimpleInfoList(List.of(ID))).thenReturn(List.of(course));
        when(courses.getBaseInfoBatch(List.of(ID))).thenReturn(List.of(detail(ID)));
        when(courses.getCourseBaseInfoById(ID)).thenReturn(detail(ID));
        var callback = Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("searchCoursesByGoal")).findFirst().orElseThrow();
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("context", "CourseRecommendations");
        String result = callback.call("{\"goal\":\"已会 Java，想处理并发库存\"}", context);
        assertThat(result).contains(Long.toString(ID), "最新线程池与锁说明", "15900", "当前正式章 / 当前正式小节")
                .doesNotContain("过期索引正文");
        assertThat(recommendations.snapshot()).isEmpty();
        course.setPrice(19900);
        var selected = tools.recommendCourses(List.of(new CourseTools.Recommendation(Long.toString(ID), "学习线程安全")), context);
        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).getPriceInCents()).isEqualTo(19900);
        assertThat(selected.get(0).getReason()).isEqualTo("适用人群：有 Java 基础");
        verify(vectors).similaritySearch(argThat((SearchRequest request) -> request.getQuery().equals("已会 Java，想处理并发库存")
                && request.getTopK() == 5 && request.getSimilarityThreshold() == 0.6
                && request.getFilterExpression().toString().contains("documentType")));
        verifyNoInteractions(keywords);
    }

    @Test
    void staleIndexCannotRecommendWithdrawnExpiredOrNotYetOnSaleCourses() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit(1), hit(2), hit(3)));
        var withdrawn = course(1); withdrawn.setStatus(3);
        var expired = course(2); expired.setPurchaseEndTime(LocalDateTime.now().minusDays(1));
        var future = course(3); future.setPurchaseStartTime(LocalDateTime.now().plusDays(1));
        when(courses.getSimpleInfoList(List.of(1L, 2L, 3L))).thenReturn(List.of(withdrawn, expired, future));
        assertThat(tools.searchCoursesByGoal("并发", context)).isEmpty();
        verify(courses, never()).getCourseBaseInfoById(anyLong());
        verify(courses, never()).getBaseInfoBatch(anyList());
        assertThat(recommendations.snapshot()).isEmpty();
    }

    @Test
    void missingOrMismatchedCurrentDetailCannotEnterCandidatePool() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit(1), hit(2)));
        when(courses.getSimpleInfoList(List.of(1L, 2L))).thenReturn(List.of(course(1), course(2)));
        when(courses.getBaseInfoBatch(List.of(1L, 2L))).thenReturn(List.of(detail(99)));
        assertThat(tools.searchCoursesByGoal("并发", context)).isEmpty();
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("2", "猜测")), context)).isEmpty();
    }

    @Test
    void emptyRetrievalDoesNotCallBusinessOrKeywordSearch() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        assertThat(tools.searchCoursesByGoal("无匹配目标", context)).isEmpty();
        verifyNoInteractions(courses, keywords);
    }

    @Test
    void failedRetrievalOrBusinessLookupSurfacesFailureWithoutKeywordFallback() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("private ES endpoint"));
        assertThatThrownBy(() -> tools.searchCoursesByGoal("并发", context))
                .matches(CourseSemanticSearch::isRetrievalFailure);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit(ID)));
        when(courses.getSimpleInfoList(List.of(ID))).thenThrow(new IllegalStateException("business unavailable"));
        assertThatThrownBy(() -> tools.searchCoursesByGoal("并发", context))
                .matches(CourseSemanticSearch::isRetrievalFailure);
        assertThat(recommendations.snapshot()).isEmpty();
        verifyNoInteractions(keywords);
    }

    @Test
    void invalidIndexMetadataFailsAndDuplicateIdsAreDeduplicated() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("text", Map.of("courseId", "bad"))));
        assertThatThrownBy(() -> tools.searchCoursesByGoal("并发", context))
                .matches(CourseSemanticSearch::isRetrievalFailure);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit(ID), hit(ID)));
        when(courses.getSimpleInfoList(List.of(ID))).thenReturn(List.of(course(ID)));
        when(courses.getBaseInfoBatch(List.of(ID))).thenReturn(List.of(detail(ID)));
        assertThat(tools.searchCoursesByGoal("并发", context)).hasSize(1);
    }

    @Test
    void cancelledOrOtherRequestsCannotReuseSemanticCandidates() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit(ID)));
        when(courses.getSimpleInfoList(List.of(ID))).thenReturn(List.of(course(ID)));
        when(courses.getBaseInfoBatch(List.of(ID))).thenReturn(List.of(detail(ID)));
        assertThat(tools.searchCoursesByGoal("并发", context)).hasSize(1);
        var other = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations()));
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation(Long.toString(ID), "跨请求")), other)).isEmpty();
        recommendations.close();
        assertThat(tools.searchCoursesByGoal("并发", context)).isEmpty();
        assertThat(recommendations.snapshot()).isEmpty();
    }

    @Test
    void batchesOnlyAvailableCoursesAndKeepsSimilarityOrderWhenDetailsArriveReversed() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit(3), hit(1), hit(2)));
        var withdrawn = course(2); withdrawn.setStatus(3);
        when(courses.getSimpleInfoList(List.of(3L, 1L, 2L)))
                .thenReturn(List.of(course(1), withdrawn, course(3)));
        when(courses.getBaseInfoBatch(List.of(3L, 1L))).thenReturn(List.of(detail(1), detail(3)));

        var candidates = tools.searchCoursesByGoal("并发", context);

        assertThat(candidates).extracting(value -> value.course().getCourseId()).containsExactly("3", "1");
        assertThat(candidates).extracting(value -> value.info().getId()).containsExactly(3L, 1L);
        verify(courses).getSimpleInfoList(List.of(3L, 1L, 2L));
        verify(courses).getBaseInfoBatch(List.of(3L, 1L));
        verify(courses, never()).getCourseBaseInfoById(anyLong());
        verifyNoMoreInteractions(courses);
    }

    @Test
    void batchDetailFailureSurfacesWithoutPartialCandidatesOrKeywordFallback() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit(ID)));
        when(courses.getSimpleInfoList(List.of(ID))).thenReturn(List.of(course(ID)));
        when(courses.getBaseInfoBatch(List.of(ID))).thenThrow(new IllegalStateException("batch unavailable"));

        assertThatThrownBy(() -> tools.searchCoursesByGoal("并发", context))
                .matches(CourseSemanticSearch::isRetrievalFailure);
        assertThat(recommendations.snapshot()).isEmpty();
        verifyNoInteractions(keywords);
    }

    private Document hit(long id) { return new Document("过期索引正文", Map.of("courseId", Long.toString(id))); }

    private CourseSimpleInfoDTO course(long id) {
        var value = new CourseSimpleInfoDTO();
        value.setId(id); value.setName("Java 并发"); value.setStatus(2); value.setPrice(15900); value.setFree(false);
        value.setValidDuration(12);
        return value;
    }

    private CourseBaseInfoDTO detail(long id) {
        var value = new CourseBaseInfoDTO();
        value.setId(id); value.setName("Java 并发"); value.setPrice(15900);
        value.setUsePeople("有 Java 基础"); value.setDetail("最新线程池与锁说明");
        value.setCatalogue(List.of("当前正式章 / 当前正式小节"));
        return value;
    }
}
