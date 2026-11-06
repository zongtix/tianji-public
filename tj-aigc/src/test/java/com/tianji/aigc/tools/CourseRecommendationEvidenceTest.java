package com.tianji.aigc.tools;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CourseRecommendationEvidenceTest {
    private final CourseClient courses = mock(CourseClient.class);
    private final SearchClient search = mock(SearchClient.class);
    private final CourseTools tools = new CourseTools(courses, search);
    private final CourseRecommendations recommendations = new CourseRecommendations();
    private final ToolContext context = context(recommendations);

    @Test
    void keywordHitCannotProduceRecommendationUntilThisRequestReceivesItsDetail() {
        givenCourse();
        tools.searchCourses("Java", context);
        var selections = List.of(new CourseTools.Recommendation("1", "适合零基础"));

        assertThat(tools.recommendCourses(selections, context)).isEmpty();
        assertThat(recommendations.snapshot()).isEmpty();
        assertThat(recommendations.hasSelectedCourses()).isFalse();

        tools.queryCourseById(1L, context);
        assertThat(recommendations.hasSelectedCourses()).isFalse();
        assertThat(tools.recommendCourses(selections, context)).singleElement()
                .satisfies(card -> assertThat(card.getCourseId()).isEqualTo("1"));
        assertThat(recommendations.hasSelectedCourses()).isTrue();
        recommendations.close();
        assertThat(recommendations.hasSelectedCourses()).isFalse();
    }

    @Test
    void detailPreviewAloneDoesNotSignalFinalRecommendation() {
        givenCourse();
        tools.queryCourseById(1L, context);
        assertThat(recommendations.snapshot()).isNotEmpty();
        assertThat(recommendations.hasSelectedCourses()).isFalse();
    }

    @Test
    void missingOrMismatchedDetailDoesNotAuthorizeARecommendation() {
        givenCourse();
        tools.searchCourses("Java", context);
        when(courses.getCourseBaseInfoById(1L)).thenReturn(null);
        assertThat(tools.queryCourseById(1L, context)).isNull();
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推测")), context)).isEmpty();

        var different = new CourseBaseInfoDTO(); different.setId(2L); different.setName("另一门课");
        when(courses.getCourseBaseInfoById(1L)).thenReturn(different);
        assertThat(tools.queryCourseById(1L, context)).isNull();
        assertThat(recommendations.snapshot()).isEmpty();
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推测")), context)).isEmpty();
    }

    @Test
    void detailFromAnotherRequestDoesNotAuthorizeTheSameKeywordCandidate() {
        givenCourse();
        tools.queryCourseById(1L, context);
        var other = new CourseRecommendations();
        var otherContext = context(other);
        tools.searchCourses("Java", otherContext);
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "沿用旧详情")), otherContext))
                .isEmpty();
        assertThat(other.snapshot()).isEmpty();
    }

    private void givenCourse() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        var course = new CourseSimpleInfoDTO();
        course.setId(1L); course.setName("Java 入门"); course.setStatus(2); course.setPrice(1999);
        when(courses.getSimpleInfoList(List.of(1L))).thenReturn(List.of(course));
        var detail = new CourseBaseInfoDTO();
        detail.setId(1L); detail.setName("Java 入门"); detail.setUsePeople("初学者");
        detail.setDetail("Java 变量、控制流程和面向对象基础");
        when(courses.getCourseBaseInfoById(1L)).thenReturn(detail);
    }

    private ToolContext context(CourseRecommendations values) {
        return new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, values));
    }
}
