package com.tianji.search.impl;

import com.tianji.api.dto.course.CoursePopularityDTO;
import com.tianji.common.exceptions.CommonException;
import com.tianji.search.controller.CourseController;
import com.tianji.search.service.ICourseService;
import com.tianji.search.service.ISearchService;
import com.tianji.search.service.impl.SearchServiceImpl;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.common.bytes.BytesArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CoursePopularityTest {
    private final RestHighLevelClient client = mock(RestHighLevelClient.class);
    private final SearchServiceImpl service = new SearchServiceImpl();

    @BeforeEach
    void prepare() {
        ReflectionTestUtils.setField(service, "restClient", client);
        ReflectionTestUtils.setField(service, "courseIndex", "test-courses");
    }

    @Test
    void browsesWithoutKeywordByEnrollmentWithBoundedStableSort() throws Exception {
        respond(hit(2, "{\"sold\":500}"), hit(1, "{\"sold\":100}"));
        assertThat(service.queryPopularCourses(null, null)).extracting(CoursePopularityDTO::getId)
                .containsExactly(2L, 1L);
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(request.capture(), any(RequestOptions.class));
        assertThat(request.getValue().indices()).containsExactly("test-courses");
        var source = request.getValue().source();
        assertThat(source.size()).isEqualTo(20);
        assertThat(source.sorts().get(0).toString()).contains("sold", "desc", "_last");
        assertThat(source.sorts().get(1).toString()).contains("id", "asc");
        assertThat(source.query().toString()).doesNotContain("match_phrase", "热门");
        assertThat(source.fetchSource().includes()).containsExactly("id", "sold");
    }

    @Test
    void filtersFreeCoursesAndExplicitDirectionInSearch() throws Exception {
        respond();
        assertThat(service.queryPopularCourses(true, " Java ")).isEmpty();
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(request.capture(), any(RequestOptions.class));
        assertThat(request.getValue().source().query().toString()).contains("free", "true", "match_phrase", "Java");
    }

    @Test
    void keepsUnknownEnrollmentDistinctFromZero() throws Exception {
        respond(hit(1, "{}"), hit(2, "{\"sold\":0}"), hit(3, "{\"sold\":-1}"));
        var found = service.queryPopularCourses(null, null);
        assertThat(found.get(0).getSold()).isNull();
        assertThat(found.get(1).getSold()).isZero();
        assertThat(found.get(2).getSold()).isNull();
    }

    @Test
    void queryFailureDoesNotBecomeEmptyResults() throws Exception {
        when(client.search(any(SearchRequest.class), any(RequestOptions.class))).thenThrow(new IOException("offline"));
        assertThatThrownBy(() -> service.queryPopularCourses(null, null)).isInstanceOf(CommonException.class);
    }

    @Test
    void endpointSupportsNoFiltersAndOptionalFreeDirection() throws Exception {
        var search = mock(ISearchService.class);
        var value = new CoursePopularityDTO(); value.setId(1L); value.setSold(12);
        when(search.queryPopularCourses(null, null)).thenReturn(List.of(value));
        when(search.queryPopularCourses(true, "Java")).thenReturn(List.of(value));
        var mvc = MockMvcBuilders.standaloneSetup(new CourseController(search, mock(ICourseService.class))).build();
        mvc.perform(get("/courses/popular")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1)).andExpect(jsonPath("$[0].sold").value(12));
        mvc.perform(get("/courses/popular").param("free", "true").param("keyword", "Java"))
                .andExpect(status().isOk());
        verify(search).queryPopularCourses(true, "Java");
    }

    private void respond(SearchHit... hits) throws Exception {
        var response = mock(SearchResponse.class);
        var results = mock(SearchHits.class);
        when(results.getHits()).thenReturn(hits);
        when(response.getHits()).thenReturn(results);
        when(client.search(any(SearchRequest.class), any(RequestOptions.class))).thenReturn(response);
    }

    private SearchHit hit(long id, String json) {
        var hit = new SearchHit((int) id, Long.toString(id), null, null, null);
        hit.sourceRef(new BytesArray(json)); return hit;
    }
}
