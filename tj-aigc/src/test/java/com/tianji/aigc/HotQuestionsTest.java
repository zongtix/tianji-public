package com.tianji.aigc;

import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.controller.SessionController;
import com.tianji.aigc.mapper.ChatSessionMapper;
import com.tianji.aigc.service.impl.ChatSessionServiceImpl;
import com.tianji.aigc.vo.SessionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HotQuestionsTest {
    private final ChatSessionMapper mapper = mock(ChatSessionMapper.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SessionProperties properties = new SessionProperties();
        properties.setExamples(List.of(
                new SessionVO.Example("Java", "Question 1"),
                new SessionVO.Example("Java", "Question 2"),
                new SessionVO.Example("Spring", "Question 3"),
                new SessionVO.Example("Spring", "Question 4")));
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(properties, mock(ChatMemory.class), new com.tianji.aigc.knowledge.KnowledgeBaseAccess(), mock(com.tianji.aigc.service.ChatGenerationStore.class), mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        mvc = MockMvcBuilders.standaloneSetup(new SessionController(service)).build();
    }

    @Test
    void hotQuestionsDefaultToThreeAndNeverCreateSessions() throws Exception {
        mvc.perform(get("/session/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].title").isString())
                .andExpect(jsonPath("$[0].describe").isString());
        verifyNoInteractions(mapper);
    }

    @Test
    void hotQuestionsHonorRequestedCount() throws Exception {
        mvc.perform(get("/session/hot").param("n", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        verifyNoInteractions(mapper);
    }
}
