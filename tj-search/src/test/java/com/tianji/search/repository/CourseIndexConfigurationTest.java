package com.tianji.search.repository;

import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.user.UserClient;
import com.tianji.search.config.InterestsProperties;
import com.tianji.search.repository.impl.CourseRepositoryImpl;
import com.tianji.search.service.IInterestsService;
import com.tianji.search.service.impl.SearchServiceImpl;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CourseIndexConfigurationTest {
    private final RestHighLevelClient client = mock(RestHighLevelClient.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CourseRepositoryImpl.class, SearchServiceImpl.class)
            .withBean(RestHighLevelClient.class, () -> client)
            .withBean(UserClient.class, () -> mock(UserClient.class))
            .withBean(IInterestsService.class, () -> mock(IInterestsService.class))
            .withBean(CategoryCache.class, () -> mock(CategoryCache.class))
            .withBean(InterestsProperties.class, InterestsProperties::new);

    @Test void defaultIndexRemainsCourse() throws Exception {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            context.getBean(CourseRepository.class).incrementSold(List.of(1L), 1);
            verify(client).bulk(argThat(request -> usesOnly(request, "course")), eq(RequestOptions.DEFAULT));
        });
    }

    @Test void repositoryAndSearchUseConfiguredIndex() throws Exception {
        SearchResponse response = mock(SearchResponse.class);
        SearchHits hits = mock(SearchHits.class);
        when(response.getHits()).thenReturn(hits);
        when(hits.getHits()).thenReturn(new SearchHit[0]);
        when(client.search(any(), any())).thenReturn(response);
        runner.withPropertyValues("tj.search.course-index=tj_upgrade_course_fixture").run(context -> {
            assertNull(context.getStartupFailure());
            context.getBean(CourseRepository.class).incrementSold(List.of(1L, 2L), 2);
            verify(client).bulk(argThat(request -> usesOnly(request, "tj_upgrade_course_fixture")), eq(RequestOptions.DEFAULT));
            assertTrue(context.getBean(SearchServiceImpl.class).queryCoursesIdByName("fixture").isEmpty());
            verify(client).search(argThat(request -> List.of(request.indices()).equals(List.of("tj_upgrade_course_fixture"))),
                    eq(RequestOptions.DEFAULT));
        });
    }

    private boolean usesOnly(BulkRequest request, String index) {
        return request.requests().size() > 0 && request.requests().stream().allMatch(item -> index.equals(item.index()));
    }
}
