package com.tianji.aigc.tools;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M8：CourseTools 单回合入口预算；新回合计数器重置；无 TURN_CALLS_KEY 时不退化。 */
class CourseToolsCallBudgetTest {
    private final CourseClient courses = mock(CourseClient.class);
    private final SearchClient search = mock(SearchClient.class);

    @Test
    void ninthExternalCallInSameTurnThrowsLimit() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(anyList())).thenReturn(List.of(simple(1L, "Java课")));
        var tools = new CourseTools(courses, search, null, (id, scope) -> null, 8);
        var counter = new AtomicInteger(0);
        var context = context(counter);

        for (int i = 0; i < 8; i++) {
            assertThat(tools.searchCourses("Java", context)).hasSize(1);
        }
        assertThatThrownBy(() -> tools.searchCourses("Java", context))
                .isInstanceOf(CourseToolCallLimitException.class);
        verify(search, times(8)).queryCoursesIdByName("Java");
    }

    @Test
    void freshTurnCounterResetsBudget() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(anyList())).thenReturn(List.of(simple(1L, "Java课")));
        var tools = new CourseTools(courses, search, null, (id, scope) -> null, 2);

        var first = context(new AtomicInteger(0));
        tools.searchCourses("Java", first);
        tools.searchCourses("Java", first);
        assertThatThrownBy(() -> tools.searchCourses("Java", first))
                .isInstanceOf(CourseToolCallLimitException.class);

        var second = context(new AtomicInteger(0));
        assertThat(tools.searchCourses("Java", second)).hasSize(1);
    }

    @Test
    void newCourseToolsInstanceAndFreshCounterResetBudgetIndependently() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(anyList())).thenReturn(List.of(simple(1L, "Java课")));
        var first = new CourseTools(courses, search, null, (id, scope) -> null, 1);
        var second = new CourseTools(courses, search, null, (id, scope) -> null, 1);
        // 预算在每回合 toolContext 计数器上，不绑死单例；新实例 + 新计数器均可独立使用满额。
        assertThat(first.searchCourses("Java", context(new AtomicInteger(0)))).hasSize(1);
        assertThat(second.searchCourses("Java", context(new AtomicInteger(0)))).hasSize(1);
    }

    @Test
    void withoutTurnCounterNormalSearchStillWorks() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L));
        when(courses.getSimpleInfoList(anyList())).thenReturn(List.of(simple(1L, "Java课")));
        var tools = new CourseTools(courses, search);
        var context = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations()));
        for (int i = 0; i < 12; i++) {
            assertThat(tools.searchCourses("Java", context)).hasSize(1);
        }
    }

    private static ToolContext context(AtomicInteger calls) {
        Map<String, Object> values = new HashMap<>();
        values.put(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations());
        values.put(CourseTools.TURN_CALLS_KEY, calls);
        return new ToolContext(values);
    }

    private static CourseSimpleInfoDTO simple(Long id, String name) {
        var value = new CourseSimpleInfoDTO();
        value.setId(id);
        value.setName(name);
        value.setStatus(2);
        value.setPrice(100);
        value.setFree(false);
        return value;
    }
}
