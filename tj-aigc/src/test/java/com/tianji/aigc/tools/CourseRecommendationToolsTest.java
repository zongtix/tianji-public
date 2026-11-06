package com.tianji.aigc.tools;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CourseRecommendationToolsTest {
    private final CourseClient courses = mock(CourseClient.class);
    private final SearchClient search = mock(SearchClient.class);
    private final CourseTools tools = new CourseTools(courses, search);
    private final CourseRecommendations recommendations = new CourseRecommendations();
    private final ToolContext context = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, recommendations));

    @Test
    void detailQueryCreatesCardWithoutSearchOrRecommendation() {
        queryDetail(9007199254740993L);
        assertThat(recommendations.snapshot()).hasSize(1);
        var card = recommendations.snapshot().get(0);
        assertThat(card.getCourseId()).isEqualTo("9007199254740993");
        assertThat(card.getName()).isEqualTo("Java 入门");
        assertThat(card.getPriceInCents()).isEqualTo(1999);
        assertThat(card.getReason()).isNull();
        verifyNoInteractions(search);
        assertThat(recommendations.contains(card.getCourseId())).isFalse();
    }

    @Test
    void finalRecommendationOverridesDetailsIncludingLaterQueries() {
        queryDetail(1L);
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(2L));
        when(courses.getSimpleInfoList(List.of(2L))).thenReturn(List.of(course(2L, 2)));
        tools.searchCourses("Java", context);
        verifyDetail(2L);
        tools.recommendCourses(List.of(new CourseTools.Recommendation("2", "适合初学者")), context);
        queryDetail(3L);
        assertThat(recommendations.snapshot()).extracting(card -> card.getCourseId()).containsExactly("2");
    }

    @Test
    void detailCardsAreDeduplicatedLimitedAndSuppressedAfterOwnershipLoss() {
        queryDetail(1L);
        queryDetail(1L);
        queryDetail(2L);
        queryDetail(3L);
        queryDetail(4L);
        assertThat(recommendations.snapshot()).extracting(card -> card.getCourseId()).containsExactly("1", "2", "3");
        assertThat(recommendations.finish(false)).isEmpty();
        queryDetail(1L);
        assertThat(recommendations.snapshot()).isEmpty();
    }

    @Test
    void emptyFinalRecommendationDoesNotFallBackToDetails() {
        queryDetail(1L);
        tools.recommendCourses(List.of(), context);
        assertThat(recommendations.snapshot()).isEmpty();
    }

    private void queryDetail(long id) {
        when(courses.getSimpleInfoList(List.of(id))).thenReturn(List.of(course(id, 2)));
        verifyDetail(id);
    }

    private void verifyDetail(long id) {
        var detail = new CourseBaseInfoDTO();
        detail.setId(id);
        detail.setName("Java 入门");
        detail.setPrice(1999);
        detail.setUsePeople("熟练使用Java的开发者");
        when(courses.getCourseBaseInfoById(id)).thenReturn(detail);
        var callback = java.util.Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(tools))
                .filter(value -> value.getToolDefinition().name().equals("queryCourseById"))
                .findFirst().orElseThrow();
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("context", "CourseRecommendations");
        assertThat(callback.call("{\"courseId\":" + id + "}", context)).contains("Java 入门");
    }

    @Test
    void searchDoesNotRecommendAndSelectionUsesRealData() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(9007199254740993L, 2L, 3L));
        var eligible = course(9007199254740993L, 2);
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(eligible, course(2L, 3), course(3L, 1)));
        var found = tools.searchCourses("Java", context);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCourseId()).isEqualTo("9007199254740993");
        assertThat(recommendations.snapshot()).isEmpty();

        verifyDetail(9007199254740993L);
        eligible.setPrice(2999);
        var cards = tools.recommendCourses(List.of(new CourseTools.Recommendation("9007199254740993", "适合学习基础语法")), context);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getPriceInCents()).isEqualTo(2999);
        assertThat(cards.get(0).getName()).isEqualTo("Java 入门");
        assertThat(cards.get(0).getReason()).isEqualTo("适用人群：熟练使用Java的开发者");
    }

    @Test
    void repeatedDetailQueryRemovesOldCardWhenCourseBecomesDemo() {
        queryDetail(1L);
        var demo = new CourseBaseInfoDTO();
        demo.setId(1L);
        demo.setName("Java 入门");
        demo.setDetail("AI联调演示数据");
        when(courses.getCourseBaseInfoById(1L)).thenReturn(demo);
        tools.queryCourseById(1L, context);
        assertThat(recommendations.snapshot()).isEmpty();
    }

    @Test
    void demoSimpleNameIsIncludedInFactWarning() {
        var simple = course(1L, 2);
        simple.setName("【AI演示】Java 入门");
        when(courses.getSimpleInfoList(List.of(1L))).thenReturn(List.of(simple));
        verifyDetail(1L);
        assertThat(CourseFactAnswer.answer("Java 入门课适用人群是什么？", recommendations))
                .contains("演示数据");
        assertThat(recommendations.snapshot()).isEmpty();
    }

    @Test
    void changedDetailIsNotRecommended() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2)));
        tools.searchCourses("Java", context);
        verifyDetail(1L);
        var changed = new CourseBaseInfoDTO();
        changed.setId(1L);
        changed.setName("Java 入门");
        changed.setUsePeople("零基础");
        changed.setDetail("AI联调演示数据");
        when(courses.getCourseBaseInfoById(1L)).thenReturn(changed);
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推荐")), context)).isEmpty();
        assertThat(CourseFactAnswer.answer("Java 入门课适用人群是什么？", recommendations)).contains("演示数据");
    }

    @Test
    void failedFinalDetailReadPropagatesAndClearsOldDetailCard() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2)));
        tools.searchCourses("Java", context);
        verifyDetail(1L);
        when(courses.getCourseBaseInfoById(1L)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推荐")), context))
                .isInstanceOf(IllegalStateException.class);
        assertThat(recommendations.snapshot()).isEmpty();
        assertThat(recommendations.canRecommend("1")).isFalse();
    }

    @Test
    void missingOrMismatchedFinalDetailCannotLeaveOldCard() {
        for (boolean mismatch : List.of(false, true)) {
            var state = new CourseRecommendations();
            var ctx = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, state));
            when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
            when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2)));
            var original = new CourseBaseInfoDTO(); original.setId(1L); original.setName("Java 入门");
            when(courses.getCourseBaseInfoById(1L)).thenReturn(original);
            tools.searchCourses("Java", ctx);
            tools.queryCourseById(1L, ctx);
            var wrong = new CourseBaseInfoDTO(); wrong.setId(2L);
            when(courses.getCourseBaseInfoById(1L)).thenReturn(mismatch ? wrong : null);
            assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推荐")), ctx)).isEmpty();
            assertThat(state.detailFacts()).isEmpty();
        }
    }

    @Test
    void changedNonDemoDetailCannotUseOldRecommendationEvidence() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2)));
        tools.searchCourses("Java", context);
        verifyDetail(1L);
        var changed = new CourseBaseInfoDTO(); changed.setId(1L); changed.setName("Java 入门");
        changed.setUsePeople("零基础");
        when(courses.getCourseBaseInfoById(1L)).thenReturn(changed);
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推荐")), context)).isEmpty();
        assertThat(recommendations.canRecommend("1")).isFalse();
    }

    @Test
    void cancelledFinalDetailReadPropagatesWithoutOldCard() {
        var reader = mock(CourseDetailReader.class);
        var localTools = new CourseTools(courses, search, null, reader);
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2)));
        var detail = new CourseBaseInfoDTO(); detail.setId(1L); detail.setName("Java 入门");
        when(reader.read(eq(1L), isNull())).thenReturn(detail).thenThrow(new java.util.concurrent.CancellationException());
        localTools.searchCourses("Java", context);
        localTools.queryCourseById(1L, context);
        assertThatThrownBy(() -> localTools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推荐")), context))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(recommendations.snapshot()).isEmpty();
    }

    @Test
    void demoInDetailRemainsQueryableButCannotBeRecommended() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2)));
        var detail = new CourseBaseInfoDTO();
        detail.setId(1L);
        detail.setName("Java 入门");
        detail.setDetail("AI联调演示数据；不提供真实授课。");
        when(courses.getCourseBaseInfoById(1L)).thenReturn(detail);

        assertThat(tools.searchCourses("Java", context)).hasSize(1);
        assertThat(tools.queryCourseById(1L, context).getDetail()).contains("不提供真实授课");
        assertThat(recommendations.snapshot()).isEmpty();
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推荐")), context)).isEmpty();
        assertThat(recommendations.hasSelectedCourses()).isFalse();
    }

    @Test
    void currentDemoNameIsRejectedButRealCourseIsStillRecommended() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L, 2L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2), course(2L, 2)));
        tools.searchCourses("Java", context);
        verifyDetail(1L);
        verifyDetail(2L);
        var demo = course(1L, 2);
        demo.setName("【AI演示】Java");
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(demo, course(2L, 2)));

        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "推荐"),
                new CourseTools.Recommendation("2", "推荐")), context))
                .extracting(card -> card.getCourseId()).containsExactly("2");
        assertThat(recommendations.detailFacts()).filteredOn(fact -> fact.courseId().equals("1"))
                .singleElement().satisfies(fact -> assertThat(fact.demo()).isTrue());
    }

    @Test
    void rejectsInventedIdsAndOtherRequestCandidates() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2)));
        tools.searchCourses("Java", context);
        var other = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations()));
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "reason")), other)).isEmpty();
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("99", "reason")), context)).isEmpty();
        verify(courses, times(1)).getSimpleInfoList(any());
    }

    @Test
    void filtersExpiredAndWithdrawnCoursesAtSelectionTime() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L, 2L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2), course(2L, 4)));
        assertThat(tools.searchCourses("Java", context)).hasSize(2);
        verifyDetail(1L);
        verifyDetail(2L);
        var expired = course(1L, 2);
        expired.setPurchaseEndTime(LocalDateTime.now().minusDays(1));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(expired, course(2L, 3)));
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "reason"),
                new CourseTools.Recommendation("2", "reason")), context)).isEmpty();
    }

    @Test
    void deduplicatesLimitsAndClosesRequestResults() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L, 2L, 3L, 4L));
        var first = course(1L, 2);
        first.setFree(true);
        first.setPrice(null);
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(first, course(2L, 2), course(3L, 2), course(4L, 2)));
        tools.searchCourses("Java", context);
        for (long id = 1; id <= 4; id++) verifyDetail(id);
        var selected = tools.recommendCourses(List.of(new CourseTools.Recommendation("1", "a"),
                new CourseTools.Recommendation("1", "duplicate"), new CourseTools.Recommendation("2", "b"),
                new CourseTools.Recommendation("3", "c"), new CourseTools.Recommendation("4", "d")), context);
        assertThat(selected).extracting(card -> card.getCourseId()).containsExactly("1", "2", "3");
        assertThat(selected.get(0).getPriceInCents()).isZero();
        recommendations.close();
        assertThat(tools.recommendCourses(List.of(new CourseTools.Recommendation("4", "late")), context)).isEmpty();
        assertThat(recommendations.snapshot()).isEqualTo(selected);
    }

    @Test
    void annotatedToolsAcceptJsonAndHideRequestContextFromModelSchema() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(any())).thenReturn(List.of(course(1L, 2)));
        var callbacks = org.springframework.ai.support.ToolCallbacks.from(tools);
        var searchTool = java.util.Arrays.stream(callbacks)
                .filter(callback -> callback.getToolDefinition().name().equals("searchCourses")).findFirst().orElseThrow();
        var recommendTool = java.util.Arrays.stream(callbacks)
                .filter(callback -> callback.getToolDefinition().name().equals("recommendCourses")).findFirst().orElseThrow();
        assertThat(searchTool.getToolDefinition().inputSchema()).doesNotContain("context", "CourseRecommendations");
        assertThat(searchTool.call("{\"keyword\":\"Java\"}", context)).contains("Java 入门");
        verifyDetail(1L);
        assertThat(recommendTool.call("{\"selections\":[{\"courseId\":\"1\",\"reason\":\"适合初学者\"}]}", context))
                .contains("熟练使用Java的开发者").doesNotContain("适合初学者");
        assertThat(recommendations.snapshot()).hasSize(1);
    }

    @Test
    void emptySearchNeverInvokesUnfilteredCourseQuery() {
        when(search.queryCoursesIdByName("不存在")).thenReturn(List.of());
        assertThat(tools.searchCourses("不存在", context)).isEmpty();
        assertThat(tools.searchCourses(" ", context)).isEmpty();
        verifyNoInteractions(courses);
    }

    private CourseSimpleInfoDTO course(long id, int status) {
        var value = new CourseSimpleInfoDTO();
        value.setId(id);
        value.setName("Java 入门");
        value.setPrice(1999);
        value.setStatus(status);
        value.setCoverUrl("/java.jpg");
        return value;
    }
}
