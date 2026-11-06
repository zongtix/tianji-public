package com.tianji.search.repository;

import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.utils.UserContext;
import com.tianji.search.config.ElasticsearchClientConfiguration;
import com.tianji.search.config.InterestsProperties;
import com.tianji.search.domain.po.Course;
import com.tianji.search.domain.query.CoursePageQuery;
import com.tianji.search.domain.vo.CourseVO;
import com.tianji.search.repository.impl.CourseRepositoryImpl;
import com.tianji.search.service.IInterestsService;
import com.tianji.search.service.impl.SearchServiceImpl;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.XContentType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** Opt-in shared ES tests; never write the business course index or global stored scripts. */
@EnabledIfEnvironmentVariable(named = "TJ_SEARCH_TEST_ES_PORT", matches = "19201")
class CourseSearchIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private RestHighLevelClient client;
    private CourseRepository repository;
    private SearchServiceImpl search;
    private IInterestsService interests;
    private String index;
    private boolean created;

    @BeforeEach void prepare() throws Exception {
        index = "tj_upgrade_course_" + UUID.randomUUID().toString().replace("-", "");
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-search", Map.of(
                "spring.elasticsearch.uris", "http://127.0.0.1:19201", "tj.search.course-index", index,
                "spring.elasticsearch.username", System.getenv("TJ_SEARCH_TEST_ES_USERNAME"),
                "spring.elasticsearch.password", System.getenv("TJ_SEARCH_TEST_ES_PASSWORD"))));
        UserClient users = mock(UserClient.class);
        UserDTO teacher = new UserDTO();
        teacher.setId(900000002L);
        teacher.setName("Fixture Teacher");
        when(users.queryUserByIds(anyCollection())).thenReturn(List.of(teacher));
        interests = mock(IInterestsService.class);
        context.registerBean(UserClient.class, () -> users);
        context.registerBean(IInterestsService.class, () -> interests);
        context.registerBean(CategoryCache.class, () -> mock(CategoryCache.class));
        context.registerBean(InterestsProperties.class, () -> {
            InterestsProperties properties = new InterestsProperties();
            properties.setTopNumber(10);
            return properties;
        });
        context.register(ElasticsearchClientConfiguration.class, CourseRepositoryImpl.class, SearchServiceImpl.class);
        context.refresh();
        client = context.getBean(RestHighLevelClient.class);
        repository = context.getBean(CourseRepository.class);
        search = context.getBean(SearchServiceImpl.class);
        client.indices().create(new CreateIndexRequest(index).settings(Settings.builder()
                .put("index.number_of_shards", 1).put("index.number_of_replicas", 0)
                .put("index.analysis.filter.py.type", "pinyin")
                .put("index.analysis.filter.py.keep_full_pinyin", false)
                .put("index.analysis.filter.py.keep_joined_full_pinyin", true)
                .put("index.analysis.filter.py.keep_original", true)
                .put("index.analysis.filter.py.none_chinese_pinyin_tokenize", false)
                .put("index.analysis.filter.py.remove_duplicated_term", true)
                .put("index.analysis.analyzer.text_analyzer.tokenizer", "ik_max_word")
                .putList("index.analysis.analyzer.text_analyzer.filter", "py")).mapping("""
                {"_meta":{"owner":"tianji-platform-upgrade-test"},"properties":{
                  "name":{"type":"text","analyzer":"text_analyzer","search_analyzer":"ik_max_word"},
                  "publishTime":{"type":"date","format":"epoch_millis||strict_date_optional_time||yyyy-MM-dd HH:mm:ss"},
                  "updateTime":{"type":"date","format":"epoch_millis||strict_date_optional_time||yyyy-MM-dd HH:mm:ss"}}}
                """, XContentType.JSON), RequestOptions.DEFAULT);
        created = true;
    }

    @AfterEach void cleanup() throws Exception {
        UserContext.removeUser();
        try {
            if (created && index.matches("tj_upgrade_course_[0-9a-f]{32}")) {
                client.indices().delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT);
            }
        } finally {
            if (context != null) context.close();
        }
    }

    @Test void repositoryWritesUpdatesIncrementsAndDeletes() {
        repository.save(course(1, "Java Upgrade", false, 10, 100));
        assertEquals("Java Upgrade", repository.findById(1L).orElseThrow().getName());
        repository.updateById(1L, "price", 200);
        repository.increment(1L, "sold", 3);
        Course updated = repository.findById(1L).orElseThrow();
        assertEquals(200, updated.getPrice());
        assertEquals(13, updated.getSold());
        repository.deleteById(1L);
        assertTrue(repository.findById(1L).isEmpty());
    }

    @Test void bulkWritesAndDeletesUseOnlyTestIndex() {
        repository.saveAll(List.of(course(1, "Java", false, 1, 100), course(2, "Redis", true, 2, 200)));
        assertTrue(repository.findById(1L).isPresent());
        assertTrue(repository.findById(2L).isPresent());
        repository.deleteByIds(List.of(1L, 2L));
        assertTrue(repository.findById(1L).isEmpty());
        assertTrue(repository.findById(2L).isEmpty());
    }

    @Test void phraseSearchHighlightsAndResolvesTeacher() throws Exception {
        fixtures();
        assertEquals(List.of(1L), search.queryCoursesIdByName("Java Upgrade"));
        CoursePageQuery query = new CoursePageQuery();
        query.setKeyword("Java Upgrade");
        var page = search.queryCoursesForPortal(query);
        assertEquals(1, page.getTotal());
        assertEquals(1, page.getPages());
        String highlighted = page.getList().get(0).getName();
        assertTrue(highlighted.contains("<em>"));
        assertEquals("Java Upgrade", highlighted.replace("<em>", "").replace("</em>", ""));
        assertEquals("Fixture Teacher", page.getList().get(0).getTeacher());
    }

    @Test void filtersSortingPaginationAndEmptyResults() throws Exception {
        fixtures();
        CoursePageQuery query = new CoursePageQuery();
        query.setCategoryIdLv1(1L);
        query.setCategoryIdLv2(100L);
        query.setCategoryIdLv3(1000L);
        query.setFree(false);
        query.setType(2);
        query.setPageSize(1).setPageNo(2).setSortBy("sold").setIsAsc(false);
        var page = search.queryCoursesForPortal(query);
        assertEquals(2, page.getTotal());
        assertEquals(2, page.getPages());
        assertEquals(1L, page.getList().get(0).getId());
        query.setKeyword("missing-fixture");
        assertTrue(search.queryCoursesForPortal(query).getList().isEmpty());
    }

    @Test void recommendationsRespectFreeCategoryAndInterests() throws Exception {
        fixtures();
        assertEquals(List.of(3L), ids(search.queryFreeTopN()));
        assertEquals(List.of(2L, 1L), ids(search.queryBestTopN()));
        assertEquals(List.of(3L), ids(search.queryCourseByCateId(200L)));
        UserContext.setUser(900000001L);
        when(interests.queryMyInterestsIds()).thenReturn(List.of(200L));
        assertTrue(search.queryBestTopN().isEmpty());
        verify(interests).queryMyInterestsIds();
    }

    @Test void storedScriptUpdatesSalesOnEs8() throws Exception {
        repository.save(course(1, "Java Upgrade", false, 10, 100));
        repository.incrementSold(List.of(1L), 2);
        assertEquals(12, repository.findById(1L).orElseThrow().getSold());
    }

    @Test void chineseAndPinyinSearchUseProductionAnalyzers() throws Exception {
        repository.save(course(1, "优惠券兑换实战", false, 10, 100));
        refresh();
        assertEquals(List.of(1L), search.queryCoursesIdByName("优惠券"));
        assertEquals(List.of(1L), search.queryCoursesIdByName("youhuiquan"));
    }

    @Test void dateRangeUsesElasticsearchCompatibleValues() throws Exception {
        fixtures();
        repository.updateById(1L, "updateTime", "2026-10-10 12:00:00");
        repository.updateById(2L, "updateTime", "2026-10-09 12:00:00");
        refresh();
        CoursePageQuery query = new CoursePageQuery();
        query.setBeginTime(LocalDateTime.of(2026, 10, 10, 0, 0));
        query.setEndTime(LocalDateTime.of(2026, 10, 10, 23, 59));
        assertEquals(List.of(1L), ids(search.queryCoursesForPortal(query).getList()));
    }

    private void fixtures() throws Exception {
        repository.saveAll(List.of(course(1, "Java Upgrade", false, 10, 100),
                course(2, "Redis Upgrade", false, 20, 100), course(3, "Free SQL", true, 30, 200)));
        refresh();
    }

    private void refresh() throws Exception {
        client.indices().refresh(new RefreshRequest(index), RequestOptions.DEFAULT);
    }

    private Course course(long id, String name, boolean free, int sold, long category) {
        Course course = new Course();
        course.setId(id);
        course.setName(name);
        course.setFree(free);
        course.setSold(sold);
        course.setPrice(100);
        course.setType(2);
        course.setCategoryIdLv1(1L);
        course.setCategoryIdLv2(category);
        course.setCategoryIdLv3(category * 10);
        course.setTeacher(900000002L);
        course.setPublishTime(LocalDateTime.of(2026, 10, (int) id, 12, 0));
        return course;
    }

    private List<Long> ids(List<CourseVO> courses) {
        return courses.stream().map(CourseVO::getId).collect(Collectors.toList());
    }
}
