package com.tianji.aigc.knowledge.course;

import com.tianji.aigc.knowledge.KnowledgeDocumentIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CourseDocumentServiceTest {
    private final VectorStore vectors = mock(VectorStore.class);
    private final KnowledgeDocumentIndex index = mock(KnowledgeDocumentIndex.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private JdbcTemplate jdbc;
    private CourseDocumentService service;
    private static final long COURSE_ID = 910000000000010003L;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE course(id BIGINT PRIMARY KEY, name VARCHAR(80), price INT, status INT,
                purchase_start_time TIMESTAMP, purchase_end_time TIMESTAMP, deleted INT DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE course_content(id BIGINT PRIMARY KEY, course_introduce VARCHAR(512),
                use_people VARCHAR(512), course_detail VARCHAR(1024), deleted INT DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE course_catalogue(id BIGINT PRIMARY KEY,course_id BIGINT,name VARCHAR(100),
                type INT,parent_catalogue_id BIGINT,c_index INT,deleted INT DEFAULT 0)
                """);
        jdbc.update("INSERT INTO course(id,name,price,status,purchase_start_time,purchase_end_time) VALUES(?,?,?,?,?,?)",
                COURSE_ID, "Java 并发", 15900, 2, LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0));
        jdbc.update("INSERT INTO course_content(id,course_introduce,use_people,course_detail) VALUES(?,?,?,?)",
                COURSE_ID, "处理并发库存扣减", "有 Java 基础", "<p>线程池&nbsp;与锁</p><script>不要保留脚本正文</script>");
        service = new CourseDocumentService(jdbc, vectors, index, "text-embedding-v4", 1024, clock);
    }

    @Test
    void buildsTraceablePlainTextWithStableIdsAndNoPriceSnapshot() {
        var course = service.findCourse(COURSE_ID).orElseThrow();
        var first = service.toDocument(course);
        var second = service.toDocument(course);
        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getMetadata()).containsEntry("courseId", Long.toString(COURSE_ID))
                .containsEntry("documentId", "course-" + COURSE_ID)
                .containsEntry("source", "tj_course.course+course_content")
                .containsEntry("embeddingDimensions", 1024).doesNotContainKeys("price", "status");
        assertThat(first.getText()).contains("Java 并发", "处理并发库存扣减", "有 Java 基础", "线程池 与锁")
                .doesNotContain("<p>", "&nbsp;", "不要保留脚本正文", "15900");
        verifyNoInteractions(vectors, index);
    }

    @Test
    void skipsIdenticalImportAndPriceChangesWithoutCallingEmbedding() throws IOException {
        when(index.countChunks("course-" + COURSE_ID, null)).thenReturn(1L);
        assertThat(service.importCourse(COURSE_ID).status()).isEqualTo("UNCHANGED");
        jdbc.update("UPDATE course SET price=19900 WHERE id=?", COURSE_ID);
        assertThat(service.importCourse(COURSE_ID).status()).isEqualTo("UNCHANGED");
        verifyNoInteractions(vectors);
    }

    @Test
    void rejectsChangedContentWithoutOverwritingExistingVectors() throws IOException {
        when(index.countChunks(eq("course-" + COURSE_ID), notNull())).thenReturn(1L);
        assertThatThrownBy(() -> service.importCourse(COURSE_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不同正文或向量模型版本");
        verifyNoInteractions(vectors);
    }

    @Test
    void retriesFailedWriteWithSameDocumentIdAndSurfacesTheFailure() throws IOException {
        doThrow(new IllegalStateException("embedding failed")).doNothing().when(vectors).add(anyList());
        assertThatThrownBy(() -> service.importCourse(COURSE_ID)).hasMessage("embedding failed");
        assertThat(service.importCourse(COURSE_ID).status()).isEqualTo("IMPORTED");
        var documents = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectors, times(2)).add(documents.capture());
        Document first = (Document) documents.getAllValues().get(0).get(0);
        Document retried = (Document) documents.getAllValues().get(1).get(0);
        assertThat(first.getId()).isEqualTo(retried.getId());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void doesNotEmbedUnavailableStatuses(int status) throws IOException {
        jdbc.update("UPDATE course SET status=? WHERE id=?", status, COURSE_ID);
        assertThat(service.importCourse(COURSE_ID).status()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(vectors, index);
    }

    @Test
    void rejectsExpiredCoursesAtTheExactEndTimeAndCoursesNotYetOnSale() throws IOException {
        jdbc.update("UPDATE course SET purchase_end_time=? WHERE id=?", LocalDateTime.now(clock), COURSE_ID);
        assertThat(service.importCourse(COURSE_ID).status()).isEqualTo("UNAVAILABLE");
        jdbc.update("UPDATE course SET purchase_end_time=?,purchase_start_time=? WHERE id=?",
                LocalDateTime.now(clock).plusDays(2), LocalDateTime.now(clock).plusDays(1), COURSE_ID);
        assertThat(service.importCourse(COURSE_ID).status()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(vectors, index);
    }

    @Test
    void acceptsFinishedCoursesAndTheExactPurchaseStartTime() throws IOException {
        jdbc.update("UPDATE course SET status=4,purchase_start_time=? WHERE id=?", LocalDateTime.now(clock), COURSE_ID);
        assertThat(service.importCourse(COURSE_ID).status()).isEqualTo("IMPORTED");
        verify(vectors).add(anyList());
    }

    @Test
    void rejectsDeletedCourseOrMissingFormalContentWithoutEmbedding() {
        jdbc.update("UPDATE course SET deleted=1 WHERE id=?", COURSE_ID);
        assertThatThrownBy(() -> service.importCourse(COURSE_ID)).hasMessageContaining("课程不存在");
        jdbc.update("UPDATE course SET deleted=0 WHERE id=?", COURSE_ID);
        jdbc.update("UPDATE course_content SET deleted=1 WHERE id=?", COURSE_ID);
        assertThatThrownBy(() -> service.importCourse(COURSE_ID)).hasMessageContaining("正文缺失");
        verifyNoInteractions(vectors, index);
    }

    @Test
    void retrievalUsesServerScopeAndRechecksCurrentBusinessState() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("旧索引中的课程文字", Map.of("courseId", Long.toString(COURSE_ID)))));
        assertThat(service.search("  并发库存  ", List.of(COURSE_ID))).extracting(CourseDocumentService.Match::name)
                .containsExactly("Java 并发");
        verify(vectors).similaritySearch(argThat((SearchRequest request) -> request.getQuery().equals("并发库存")
                && request.getTopK() == 3 && request.getSimilarityThreshold() == 0.6
                && request.getFilterExpression() != null));
        jdbc.update("UPDATE course SET status=3 WHERE id=?", COURSE_ID);
        assertThat(service.search("并发库存", List.of(COURSE_ID))).isEmpty();
        jdbc.update("UPDATE course SET status=2,purchase_end_time=? WHERE id=?", LocalDateTime.now(clock), COURSE_ID);
        assertThat(service.search("并发库存", List.of(COURSE_ID))).isEmpty();
        jdbc.update("UPDATE course SET deleted=1 WHERE id=?", COURSE_ID);
        assertThat(service.search("并发库存", List.of(COURSE_ID))).isEmpty();
    }

    @Test
    void refusesOutOfScopeResultsAndPropagatesRetrievalErrors() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("其他课程", Map.of("courseId", "1"))));
        assertThat(service.search("并发库存", List.of(COURSE_ID))).isEmpty();
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("ES unavailable"));
        assertThatThrownBy(() -> service.search("并发库存", List.of(COURSE_ID))).hasMessage("ES unavailable");
    }

    @Test
    void contentAndEmbeddingModelChangesProduceDifferentVersions() {
        var course = service.findCourse(COURSE_ID).orElseThrow();
        String id = service.toDocument(course).getId();
        var otherModel = new CourseDocumentService(jdbc, vectors, index, "another-model", 1024, clock);
        assertThat(otherModel.toDocument(course).getId()).isNotEqualTo(id);
        jdbc.update("UPDATE course_content SET course_detail='新版线程池实践' WHERE id=?", COURSE_ID);
        assertThat(service.toDocument(service.findCourse(COURSE_ID).orElseThrow()).getId()).isNotEqualTo(id);
    }

    @Test
    void includesOrderedPublishedChapterEvidenceAndVersionsChangesWithoutUsingDraftsOrExercises() {
        String original = service.toDocument(service.findCourse(COURSE_ID).orElseThrow()).getId();
        jdbc.update("INSERT INTO course_catalogue VALUES(1,?,'认识微服务',1,0,1,0),"
                + "(2,?,'架构演变',2,1,1,0),(3,?,'Spring Cloud',2,1,2,0),"
                + "(4,?,'隐藏章',1,0,2,1),(5,?,'隐藏章下的小节',2,4,1,0),"
                + "(6,?,'练习题',3,1,3,0),(7,?,'孤立小节',2,99,1,0),"
                + "(8,99,'其他课程',1,0,1,0),(9,?,'跨课程小节',2,8,1,0)",
                COURSE_ID, COURSE_ID, COURSE_ID, COURSE_ID, COURSE_ID, COURSE_ID, COURSE_ID, COURSE_ID);
        var course = service.findCourse(COURSE_ID).orElseThrow();
        assertThat(course.catalogue()).containsExactly("认识微服务", "认识微服务 / 架构演变", "认识微服务 / Spring Cloud");
        var document = service.toDocument(course);
        assertThat(document.getText()).contains("正式章节目录（仅标题）：", "认识微服务 / 架构演变", "Spring Cloud")
                .doesNotContain("隐藏章", "练习题", "孤立小节", "其他课程", "跨课程小节");
        assertThat(document.getMetadata()).containsEntry("source", "tj_course.course+course_content+course_catalogue");
        assertThat(document.getId()).isNotEqualTo(original);
        assertThat(service.toDocument(course).getId()).isEqualTo(document.getId());
        jdbc.update("UPDATE course_catalogue SET name='技术对比' WHERE id=3");
        assertThat(service.toDocument(service.findCourse(COURSE_ID).orElseThrow()).getId()).isNotEqualTo(document.getId());
        verifyNoInteractions(vectors, index);
    }
}
