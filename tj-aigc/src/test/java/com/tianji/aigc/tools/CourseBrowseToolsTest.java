package com.tianji.aigc.tools;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.dto.course.CoursePopularityDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CourseBrowseToolsTest {
    private final CourseClient courses = mock(CourseClient.class);
    private final SearchClient search = mock(SearchClient.class);
    private final CourseTools tools = new CourseTools(courses, search);
    private final CourseRecommendations recommendations = new CourseRecommendations();
    private final ToolContext context = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, recommendations));

    @Test
    void browsesWithoutGoalKeepsRankingAndUsesLiveDetailsAndFinalPrice() {
        when(search.queryPopularCourses(null, null)).thenReturn(List.of(popular(2, 500), popular(1, 100)));
        var first = course(2); first.setFree(false); first.setPrice(1999);
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1), first));
        when(courses.getBaseInfoBatch(List.of(2L, 1L))).thenReturn(List.of(detail(1), detail(2)));
        when(courses.getCourseBaseInfoById(2L)).thenReturn(detail(2));
        var found = tools.browseCourses(null, null, context);
        assertThat(found.hasEnrollmentData()).isTrue();
        assertThat(found.courses()).extracting(value -> value.course().getCourseId()).containsExactly("2", "1");
        assertThat(found.courses().get(0).enrollmentCount()).isEqualTo(500);
        assertThat(found.courses().get(0).info().getDetail()).isEqualTo("正式课程介绍");
        assertThat(recommendations.snapshot()).isEmpty();
        first.setPrice(2999);
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("2", "课程浏览")), context))
                .singleElement().satisfies(card -> assertThat(card.getPriceInCents()).isEqualTo(2999));
        verify(search, never()).queryCoursesIdByName(any());
    }

    @Test
    void excludesDemoWithdrawnExpiredMissingDetailAndStaleFreeFlags() {
        when(search.queryPopularCourses(true, "Java")).thenReturn(List.of(
                popular(1, 900), popular(2, 800), popular(3, 700), popular(4, 600),
                popular(5, 500), popular(6, 400), popular(7, 300), popular(8, 200)));
        var demo = course(1); demo.setName("【AI演示】Java");
        var withdrawn = course(2); withdrawn.setStatus(3);
        var expired = course(3); expired.setPurchaseEndTime(LocalDateTime.now().minusDays(1));
        var paid = course(4); paid.setFree(false);
        var demoDetail = detail(5); demoDetail.setDetail("AI联调演示数据；不提供真实授课。");
        var renamedDemo = detail(8); renamedDemo.setName("【AI演示】改名课程");
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(demo, withdrawn, expired, paid,
                course(5), course(6), course(7), course(8)));
        when(courses.getBaseInfoBatch(List.of(5L, 6L, 7L, 8L))).thenReturn(List.of(demoDetail, detail(7), renamedDemo));
        var found = tools.browseCourses(true, " Java ", context);
        assertThat(found.courses()).extracting(value -> value.course().getCourseId()).containsExactly("7");
        assertThat(found.courses().get(0).course().getPriceInCents()).isZero();
        assertThat(recommendations.contains("5")).isFalse();
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "演示")), context)).isEmpty();
    }

    @Test
    void missingOrZeroCountsAllowBrowsingWithoutClaimingPopularity() {
        when(search.queryPopularCourses(null, null)).thenReturn(List.of(popular(1, 0), popular(2, null)));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1), course(2)));
        when(courses.getBaseInfoBatch(any())).thenReturn(List.of(detail(1), detail(2)));
        var found = tools.browseCourses(null, null, context);
        assertThat(found.hasEnrollmentData()).isFalse();
        assertThat(found.courses()).hasSize(2);
        assertThat(found.courses().get(1).enrollmentCount()).isNull();
    }

    @Test
    void emptySearchAndInvalidInputDoNotFetchCourseDetails() {
        when(search.queryPopularCourses(null, null)).thenReturn(List.of());
        assertThat(tools.browseCourses(null, null, context).courses()).isEmpty();
        assertThat(tools.browseCourses(null, "x".repeat(101), context).courses()).isEmpty();
        verifyNoInteractions(courses);
    }

    @Test
    void toolAcceptsNullFiltersAndHidesRequestContext() {
        when(search.queryPopularCourses(null, null)).thenReturn(List.of());
        var callback = Arrays.stream(ToolCallbacks.from(tools))
                .filter(value -> value.getToolDefinition().name().equals("browseCourses")).findFirst().orElseThrow();
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("context", "CourseRecommendations");
        assertThat(callback.call("{\"free\":null,\"keyword\":null}", context)).contains("hasEnrollmentData");
        verify(search).queryPopularCourses(null, null);
    }

    private CoursePopularityDTO popular(long id, Integer count) {
        var value = new CoursePopularityDTO(); value.setId(id); value.setSold(count); return value;
    }

    private CourseSimpleInfoDTO course(long id) {
        var value = new CourseSimpleInfoDTO(); value.setId(id); value.setName("Java " + id);
        value.setStatus(2); value.setFree(true); value.setValidDuration(12); return value;
    }

    private CourseBaseInfoDTO detail(long id) {
        var value = new CourseBaseInfoDTO(); value.setId(id); value.setName("Java " + id);
        value.setDetail("正式课程介绍"); value.setFree(true); return value;
    }
}
