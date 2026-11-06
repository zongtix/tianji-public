package com.tianji.course.service.impl;

import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseIndexInfoDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.course.domain.po.Course;
import com.tianji.course.domain.po.CourseContent;
import com.tianji.course.mapper.CourseContentMapper;
import com.tianji.course.mapper.CourseCatalogueMapper;
import com.tianji.course.mapper.CourseMapper;
import com.tianji.course.service.ICourseCatalogueService;
import com.tianji.course.service.ICourseTeacherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CourseBaseInfoTest {
    private final CourseMapper courses = mock(CourseMapper.class);
    private final CourseContentMapper contents = mock(CourseContentMapper.class);
    private final CourseCatalogueMapper outlines = mock(CourseCatalogueMapper.class);
    private final ICourseCatalogueService catalogues = mock(ICourseCatalogueService.class);
    private final ICourseTeacherService teachers = mock(ICourseTeacherService.class);
    private final CourseServiceImpl service = new CourseServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", courses);
        ReflectionTestUtils.setField(service, "courseContentMapper", contents);
        ReflectionTestUtils.setField(service, "courseCatalogueMapper", outlines);
        ReflectionTestUtils.setField(service, "courseCatalogueService", catalogues);
        ReflectionTestUtils.setField(service, "courseTeacherService", teachers);
        when(courses.selectById(1L)).thenReturn(new Course().setId(1L)
                .setName("Java 入门").setPrice(1999).setValidDuration(12));
    }

    @Test
    void returnsBasicInfoAndPublishedContent() {
        when(contents.selectById(1L)).thenReturn(new CourseContent()
                .setId(1L).setUsePeople("初学者").setCourseDetail("Java 基础课程"));

        CourseBaseInfoDTO result = service.getBaseInfoById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Java 入门");
        assertThat(result.getPrice()).isEqualTo(1999);
        assertThat(result.getValidDuration()).isEqualTo(12);
        assertThat(result.getUsePeople()).isEqualTo("初学者");
        assertThat(result.getDetail()).isEqualTo("Java 基础课程");
        verifyNoInteractions(catalogues, teachers);
    }

    @Test
    void missingContentStillReturnsBasicInfo() {
        CourseBaseInfoDTO result = service.getBaseInfoById(1L);

        assertThat(result.getName()).isEqualTo("Java 入门");
        assertThat(result.getPrice()).isEqualTo(1999);
        assertThat(result.getUsePeople()).isNull();
        assertThat(result.getDetail()).isNull();
        assertThat(result.getCatalogue()).isEmpty();
    }

    @Test
    void detailAndIndexSnapshotsExposeTheSamePublishedOutlineWithoutExamOrTeacherCalls() {
        when(outlines.selectOutlineTitles(List.of(1L))).thenReturn(List.of(
                new CourseCatalogueMapper.OutlineTitle(1L, "认识微服务"),
                new CourseCatalogueMapper.OutlineTitle(1L, "认识微服务 / 架构演变")));
        var snapshot = new CourseIndexInfoDTO();
        snapshot.setId(1L);
        when(courses.selectSemanticIndexInfo(1L)).thenReturn(snapshot);

        assertThat(service.getBaseInfoById(1L).getCatalogue())
                .containsExactly("认识微服务", "认识微服务 / 架构演变");
        assertThat(service.getSemanticIndexInfo(1L).getCatalogue())
                .isEqualTo(service.getBaseInfoById(1L).getCatalogue());
        verifyNoInteractions(catalogues, teachers);
    }

    @Test
    void indexPageLoadsOutlinesInOneBatchAndKeepsCoursesWithoutChapters() {
        var first = new CourseIndexInfoDTO(); first.setId(1L);
        var second = new CourseIndexInfoDTO(); second.setId(2L);
        when(courses.selectSemanticIndexPage(0, 50)).thenReturn(List.of(first, second));
        when(outlines.selectOutlineTitles(List.of(1L, 2L))).thenReturn(List.of(
                new CourseCatalogueMapper.OutlineTitle(1L, "认识微服务")));
        var page = service.querySemanticIndexPage(0, 50);
        assertThat(page.get(0).getCatalogue()).containsExactly("认识微服务");
        assertThat(page.get(1).getCatalogue()).isEmpty();
        verify(outlines).selectOutlineTitles(List.of(1L, 2L));
        verifyNoMoreInteractions(outlines);
    }

    @Test
    void emptyOrMissingSnapshotsDoNotQueryOutlines() {
        assertThat(service.querySemanticIndexPage(0, 50)).isEmpty();
        assertThat(service.getSemanticIndexInfo(2L)).isNull();
        verifyNoInteractions(outlines);
    }

    @Test
    void freeFlagComesFromPublishedCourse() {
        when(courses.selectById(1L)).thenReturn(new Course().setId(1L)
                .setName("免费 Java 入门").setFree(1).setPrice(1999));
        CourseBaseInfoDTO result = service.getBaseInfoById(1L);
        assertThat(result.getFree()).isTrue();
        assertThat(result.getPrice()).isEqualTo(1999);
    }

    @Test
    void batchMatchesSingleDetailsIncludingMissingContentAndPreservesRequestedOrder() {
        var first = new Course().setId(1L).setName("Java 入门").setPrice(1999).setValidDuration(12);
        var second = new Course().setId(2L).setName("免费课程").setFree(1).setPrice(2999);
        var content = new CourseContent().setId(1L).setUsePeople("初学者").setCourseDetail("正式 Java 课程");
        var firstOutline = List.of(new CourseCatalogueMapper.OutlineTitle(1L, "第一章"),
                new CourseCatalogueMapper.OutlineTitle(1L, "第一章 / 第一节"));
        when(courses.selectById(2L)).thenReturn(second);
        when(contents.selectById(1L)).thenReturn(content);
        when(outlines.selectOutlineTitles(List.of(1L))).thenReturn(firstOutline);
        when(courses.selectBatchIds(List.of(2L, 1L))).thenReturn(List.of(first, second));
        when(contents.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(content));
        when(outlines.selectOutlineTitles(List.of(1L, 2L))).thenReturn(firstOutline);

        var batch = service.getBaseInfoBatch(List.of(2L, 1L));

        assertThat(batch).usingRecursiveComparison()
                .isEqualTo(List.of(service.getBaseInfoById(2L), service.getBaseInfoById(1L)));
        assertThat(batch.get(0).getFree()).isTrue();
        assertThat(batch.get(0).getDetail()).isNull();
        assertThat(batch.get(0).getCatalogue()).isEmpty();
        assertThat(batch.get(1).getCatalogue()).containsExactly("第一章", "第一章 / 第一节");
        verify(courses).selectBatchIds(List.of(2L, 1L));
        verify(contents).selectBatchIds(List.of(1L, 2L));
        verify(outlines).selectOutlineTitles(List.of(1L, 2L));
        verifyNoInteractions(catalogues, teachers);
    }

    @Test
    void emptyOrEntirelyMissingBatchDoesNotLoadContentOrOutlines() {
        assertThat(service.getBaseInfoBatch(List.of())).isEmpty();
        verifyNoInteractions(courses, contents, outlines);
        assertThat(service.getBaseInfoBatch(List.of(99L))).isEmpty();
        verify(courses).selectBatchIds(List.of(99L));
        verifyNoInteractions(contents, outlines);
    }

    @Test
    void missingIdsAreOmittedAndRepeatedIdsDoNotDuplicateBatchResults() {
        when(courses.selectBatchIds(List.of(99L, 1L, 1L))).thenReturn(List.of(
                new Course().setId(1L).setName("Java 入门")));
        assertThat(service.getBaseInfoBatch(List.of(99L, 1L, 1L)))
                .extracting(CourseBaseInfoDTO::getId).containsExactly(1L);
        verify(courses, never()).selectById(anyLong());
        verify(contents, never()).selectById(anyLong());
    }

    @Test
    void missingCourseKeepsExistingErrorSemantics() {
        assertThatThrownBy(() -> service.getBaseInfoById(2L))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(contents);
    }

    @Test
    void existingBasicQueryDoesNotReadContentOrExtraData() {
        CourseFullInfoDTO result = service.getInfoById(1L, false, false);

        assertThat(result.getName()).isEqualTo("Java 入门");
        assertThat(result.getPrice()).isEqualTo(1999);
        assertThat(result.getChapters()).isNull();
        assertThat(result.getTeacherIds()).isNull();
        verifyNoInteractions(contents, catalogues, teachers);
    }

    @Test
    void existingDetailedQueryStillLoadsCataloguesAndTeachers() {
        when(catalogues.queryCourseCatalogues(1L, true)).thenReturn(List.of());
        when(teachers.getTeacherIdOfCourse(1L)).thenReturn(List.of(3L));

        CourseFullInfoDTO result = service.getInfoById(1L, true, true);

        assertThat(result.getName()).isEqualTo("Java 入门");
        assertThat(result.getChapters()).isEmpty();
        assertThat(result.getTeacherIds()).containsExactly(3L);
        verify(catalogues).queryCourseCatalogues(1L, true);
        verify(teachers).getTeacherIdOfCourse(1L);
        verifyNoInteractions(contents);
    }
}
