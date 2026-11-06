package com.tianji.course.controller;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.course.service.ICourseService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseBaseInfoBatchHttpTest {
    @Test
    void feignContractMatchesBatchEndpointAndKeepsLongIdsAndPublishedFields() throws Exception {
        long id = 910000000000010003L;
        var detail = new CourseBaseInfoDTO();
        detail.setId(id);
        detail.setUsePeople("Java 基础");
        detail.setDetail("正式课程正文");
        detail.setCatalogue(List.of("第一章", "第一章 / 第一节"));
        var courses = mock(ICourseService.class);
        when(courses.getBaseInfoBatch(List.of(id))).thenReturn(List.of(detail));
        when(courses.getBaseInfoBatch(List.of())).thenReturn(List.of());
        var controller = new CourseController();
        ReflectionTestUtils.setField(controller, "courseService", courses);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String path = CourseClient.class.getMethod("getBaseInfoBatch", List.class)
                .getAnnotation(PostMapping.class).value()[0];
        assertThat(path).isEqualTo("/courses/base-info/batch");

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("[" + id + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].usePeople").value("Java 基础"))
                .andExpect(jsonPath("$[0].detail").value("正式课程正文"))
                .andExpect(jsonPath("$[0].catalogue[1]").value("第一章 / 第一节"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(courses).getBaseInfoBatch(List.of(id));
        verify(courses).getBaseInfoBatch(List.of());
    }
}
