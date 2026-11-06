package com.tianji.course.controller;

import com.tianji.course.service.ICourseService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseSemanticIndexHttpTest {
    private MockMvc mvc() {
        var controller = new CourseInfoController();
        ReflectionTestUtils.setField(controller, "courseService", mock(ICourseService.class));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exposesInternalSnapshotPageWithoutLoadingDrafts() throws Exception {
        mvc().perform(get("/course/semantic-index").param("afterId", "0").param("size", "50"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsUnboundedOrNegativeCursors() throws Exception {
        mvc().perform(get("/course/semantic-index").param("afterId", "-1")).andExpect(status().isBadRequest());
        mvc().perform(get("/course/semantic-index").param("size", "201")).andExpect(status().isBadRequest());
        mvc().perform(get("/course/semantic-index").param("size", "0")).andExpect(status().isBadRequest());
        mvc().perform(get("/course/semantic-index/0")).andExpect(status().isBadRequest());
    }
}
