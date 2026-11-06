package com.tianji.aigc.longterm;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.name=aigc-stream-test",
        "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false", "tj.auth.resource.enable=false",
        "spring.datasource.url=jdbc:h2:mem:user_memory_http;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=never",
        "spring.ai.openai.api-key=test-only", "spring.ai.openai.base-url=http://127.0.0.1:1",
        "spring.ai.openai.chat.options.model=test-model", "tj.swagger.enableResponseWrap=true",
        "tj.ai.long-term-memory.enabled=true",
        "tj.ai.long-term-memory.encryption-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
        "spring.ai.chat.observations.log-prompt=false", "spring.ai.chat.observations.log-completion=false"
})
@AutoConfigureMockMvc
class UserMemoryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMemoryStore store;
    @MockBean ChatService chatService;
    @MockBean ChatSessionService chatSessionService;
    @MockBean SystemPromptConfig systemPromptConfig;
    @MockBean UserMemoryWorker worker;

    @BeforeEach
    void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_memory_profile (user_id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL, epoch BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_memory_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, memory_key VARCHAR(32) NOT NULL, content_cipher TEXT NOT NULL, version BIGINT NOT NULL, source_task_id BIGINT NOT NULL, source_session_id VARCHAR(32) NOT NULL, source_request_id VARCHAR(36) NOT NULL, updated_at TIMESTAMP NOT NULL, INDEX idx_memory_user_key(user_id,memory_key,id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_memory_task (id BIGINT AUTO_INCREMENT PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE, user_id BIGINT NOT NULL, session_id VARCHAR(32) NOT NULL, epoch BIGINT NOT NULL, state VARCHAR(16) NOT NULL, payload_cipher TEXT NOT NULL, created_at TIMESTAMP NOT NULL, ready_at TIMESTAMP, claim_token VARCHAR(36), claim_until TIMESTAMP, attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP)");
        jdbc.update("DELETE FROM user_memory_task");
        jdbc.update("DELETE FROM user_memory_item");
        jdbc.update("DELETE FROM user_memory_profile");
    }

    @Test
    void gatewayStyleRequestsUseAuthenticatedUserForListEditAndSettings() throws Exception {
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-a", request, "我学过 Java", null);
        makeReady(request, "7_session-a");
        store.apply(store.claimDue(1, Duration.ZERO), List.of(new UserMemoryStore.Candidate("background", "学过 Java", request, "学过 Java")));
        var memory = store.list(7).get(0);
        mvc.perform(get("/memories").header("user-info", "8").header("x-request-from", "gateway"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(put("/memories/{id}", memory.id()).header("user-info", "8").header("x-request-from", "gateway")
                        .contentType("application/json").content("{\"version\":1,\"content\":\"其他人修改\"}"))
                .andExpect(jsonPath("$.code").value(400));
        assertThat(store.list(7).get(0).content()).isEqualTo("学过 Java");
        mvc.perform(put("/memories/{id}", memory.id()).header("user-info", "7").header("x-request-from", "gateway")
                        .contentType("application/json").content("{\"version\":1,\"content\":\"正在学 Java\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/memories").header("user-info", "7").header("x-request-from", "gateway"))
                .andExpect(jsonPath("$.data[0].id").value(Long.toString(memory.id())))
                .andExpect(jsonPath("$.data[0].content").value("正在学 Java"));
        mvc.perform(put("/memories/settings").header("user-info", "7").header("x-request-from", "gateway")
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/memories/settings").header("user-info", "7").header("x-request-from", "gateway"))
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertThat(store.context(7)).isEmpty();
    }

    @Test
    void sameCategoryFactsRemainIndividuallyVisibleEditableAndDeletable() throws Exception {
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-a", request, "我学过 Java，也学过 Python", null);
        makeReady(request, "7_session-a");
        store.apply(store.claimDue(1, Duration.ZERO), List.of(
                new UserMemoryStore.Candidate("background", "学过 Java", request, "学过 Java"),
                new UserMemoryStore.Candidate("background", "学过 Python", request, "学过 Python")));
        var java = store.list(7).get(0);
        var python = store.list(7).get(1);
        mvc.perform(get("/memories").header("user-info", "7").header("x-request-from", "gateway"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].content").value("学过 Java"))
                .andExpect(jsonPath("$.data[1].content").value("学过 Python"));
        mvc.perform(put("/memories/{id}", java.id()).header("user-info", "7").header("x-request-from", "gateway")
                        .contentType("application/json").content("{\"version\":1,\"content\":\"正在学 Java\"}"))
                .andExpect(jsonPath("$.code").value(200));
        assertThat(store.list(7).get(1)).isEqualTo(python);
        mvc.perform(delete("/memories/{id}?version=2", java.id()).header("user-info", "7").header("x-request-from", "gateway"))
                .andExpect(jsonPath("$.code").value(200));
        assertThat(store.list(7)).containsExactly(python);
    }

    @Test
    void missingIdentityCannotReadMemories() throws Exception {
        mvc.perform(get("/memories").header("x-request-from", "gateway"))
                .andExpect(jsonPath("$.code").value(401));
    }
    private boolean makeReady(String requestId, String conversationId) {
        return store.markPersisted(requestId, conversationId) && store.markReady(requestId, conversationId);
    }

}
