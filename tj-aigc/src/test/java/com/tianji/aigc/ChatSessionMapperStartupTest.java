package com.tianji.aigc;

import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.mapper.ChatSessionMapper;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.config.SystemPromptConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=aigc-mapper-test",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:aigc_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.ai.openai.api-key=test-only",
        "spring.ai.openai.base-url=http://127.0.0.1:1",
        "tj.auth.resource.enable=false"
})
class ChatSessionMapperStartupTest {
    @MockBean SystemPromptConfig systemPromptConfig;
    @Autowired ChatSessionMapper mapper;
    @Autowired ChatSessionService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationRegistersMapperAndCanPersistSession() {
        assertThat(service).isNotNull();
        jdbc.execute("""
                CREATE TABLE chat_session (
                    id BIGINT PRIMARY KEY,
                    session_id VARCHAR(32) NOT NULL,
                    user_id BIGINT NOT NULL,
                    title VARCHAR(128),
                    title_status TINYINT NOT NULL DEFAULT 2,
                    knowledge_base_id VARCHAR(64),
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    creater BIGINT,
                    updater BIGINT
                )
                """);
        ChatSession session = ChatSession.builder()
                .sessionId("mapper-startup-check")
                .userId(1L)
                .title("Mapper startup check")
                .build();

        assertThat(mapper.insert(session)).isEqualTo(1);
        assertThat(session.getId()).isNotNull();
        ChatSession saved = mapper.selectById(session.getId());
        assertThat(saved.getSessionId()).isEqualTo(session.getSessionId());
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getTitle()).isEqualTo("Mapper startup check");
        assertThat(mapper.deleteById(session.getId())).isEqualTo(1);
    }
}
