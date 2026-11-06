package com.tianji.learning.service.lesson;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CatalogueDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.lesson.LearningRecordMapper;
import com.tianji.learning.mq.message.LearningPointsMessage;
import com.tianji.learning.service.lesson.impl.LearningRecordServiceImpl;
import com.tianji.learning.service.points.IPointsMessageService;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningPointsValidationTest {
    private CourseClient courseClient;
    private LearningRecordMapper mapper;
    private IPointsMessageService messages;
    private LearningRecordServiceImpl service;
    private CatalogueDTO section;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LearningRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LearningLesson.class);
        UserContext.setUser(1001L);
        ILearningLessonService lessons = mock(ILearningLessonService.class);
        when(lessons.getById(2001L)).thenReturn(new LearningLesson().setId(2001L).setUserId(1001L)
                .setCourseId(3001L).setStatus(0).setLearnedSections(0));
        com.tianji.learning.mapper.lesson.LearningLessonMapper lessonMapper = mock(com.tianji.learning.mapper.lesson.LearningLessonMapper.class);
        when(lessonMapper.update(any(), any())).thenReturn(1);
        LambdaUpdateChainWrapper<LearningLesson> update = new LambdaUpdateChainWrapper<>(lessonMapper,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>(LearningLesson.class));
        when(lessons.lambdaUpdate()).thenReturn(update);
        courseClient = mock(CourseClient.class);
        section = new CatalogueDTO();
        section.setId(4001L);
        section.setType(2);
        section.setMediaDuration(100);
        CatalogueDTO chapter = new CatalogueDTO();
        chapter.setSections(List.of(section));
        CourseFullInfoDTO course = new CourseFullInfoDTO();
        course.setId(3001L);
        course.setSectionNum(2);
        course.setChapters(List.of(chapter));
        when(courseClient.getCourseInfoById(3001L, true, false)).thenReturn(course);
        mapper = mock(LearningRecordMapper.class);
        when(mapper.insert(any(LearningRecord.class))).thenReturn(1);
        messages = mock(IPointsMessageService.class);
        service = new LearningRecordServiceImpl(lessons, courseClient, mock(LearningRecordDelayTaskHandler.class), messages);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        // MyBatis-Plus 3.5 derives this from the real mapper proxy; these tests inject a mock.
        ReflectionTestUtils.setField(service, "entityClass", LearningRecord.class);
    }

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    private LearningRecordFormDTO request(SectionType type) {
        LearningRecordFormDTO request = new LearningRecordFormDTO();
        request.setLessonId(2001L);
        request.setSectionId(4001L);
        request.setSectionType(type);
        request.setDuration(1);
        request.setMoment(1);
        return request;
    }

    @Test
    void arbitrarySectionIdIsRejectedBeforeWritingOrAwardingPoints() {
        LearningRecordFormDTO request = request(SectionType.EXAM);
        request.setSectionId(9990001L);
        assertThrows(BizIllegalException.class, () -> service.addLearningRecord(request));
        verifyNoInteractions(mapper, messages);
    }

    @Test
    void videoCannotBeSubmittedAsExam() {
        assertThrows(BizIllegalException.class, () -> service.addLearningRecord(request(SectionType.EXAM)));
        verifyNoInteractions(mapper, messages);
    }

    @Test
    void missingCatalogueFailsClosed() {
        when(courseClient.getCourseInfoById(3001L, true, false)).thenReturn(null);
        assertThrows(BizIllegalException.class, () -> service.addLearningRecord(request(SectionType.VIDEO)));
        verifyNoInteractions(mapper, messages);
    }

    @Test
    void forgedShortDurationDoesNotCompleteVideo() {
        LearningRecordFormDTO request = request(SectionType.VIDEO);
        service.addLearningRecord(request);
        ArgumentCaptor<LearningRecord> record = ArgumentCaptor.forClass(LearningRecord.class);
        verify(mapper).insert(record.capture());
        assertFalse(record.getValue().getFinished());
        assertEquals(100, request.getDuration());
        verifyNoInteractions(messages);
    }

    @Test
    void validVideoCompletionSavesStablePointsEvent() {
        LearningRecordFormDTO request = request(SectionType.VIDEO);
        request.setMoment(50);
        service.addLearningRecord(request);
        verify(messages).saveMessage(eq("learning:2001:4001"), eq(MqConstants.Key.LEARN_SECTION),
                eq(LearningPointsMessage.of(1001L, 2001L, 4001L)));
        verify(courseClient, times(1)).getCourseInfoById(3001L, true, false);
    }

    @Test
    void realExamInCourseRemainsSupported() {
        section.setType(3);
        service.addLearningRecord(request(SectionType.EXAM));
        verify(messages).saveMessage(anyString(), eq(MqConstants.Key.LEARN_SECTION), any(LearningPointsMessage.class));
    }
}
