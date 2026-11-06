package com.tianji.aigc.longterm;

import com.tianji.aigc.mapper.ChatMemoryMessageMapper;
import com.tianji.aigc.memory.MysqlChatMemoryRepository;
import com.tianji.aigc.tools.CourseCardMetadataAdvisor;
import com.tianji.aigc.tools.CourseRecommendations;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryHandoffTest {
    private JdbcTemplate jdbc;
    private UserMemoryStore store;
    private MysqlChatMemoryRepository repository;
    private ChatMemory chatMemory;
    private ChatModel model;
    private ChatClient client;
    private String requestId;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE chat_message (id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id VARCHAR(128), message_index INT, message_json LONGTEXT, UNIQUE(conversation_id,message_index))");
        jdbc.execute("CREATE TABLE user_memory_profile (user_id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL, epoch BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE user_memory_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, memory_key VARCHAR(32) NOT NULL, content_cipher TEXT NOT NULL, version BIGINT NOT NULL, source_task_id BIGINT NOT NULL, source_session_id VARCHAR(32) NOT NULL, source_request_id VARCHAR(36) NOT NULL, updated_at TIMESTAMP NOT NULL, INDEX idx_memory_user_key(user_id,memory_key,id))");
        jdbc.execute("CREATE TABLE user_memory_task (id BIGINT AUTO_INCREMENT PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE, user_id BIGINT NOT NULL, session_id VARCHAR(32) NOT NULL, epoch BIGINT NOT NULL, state VARCHAR(16) NOT NULL, payload_cipher TEXT NOT NULL, created_at TIMESTAMP NOT NULL, ready_at TIMESTAMP, claim_token VARCHAR(36), claim_until TIMESTAMP, attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP)");
        var tx = new DataSourceTransactionManager(source);
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        store = new UserMemoryStore(jdbc, tx, new UserMemoryCipher(key));
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(ChatMemoryMessageMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessions = factory.getObject();
        repository = new MysqlChatMemoryRepository(new SqlSessionTemplate(sessions).getMapper(ChatMemoryMessageMapper.class), tx, store);
        chatMemory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).build();
        model = mock(ChatModel.class);
        client = ChatClient.builder(model).defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build();
        requestId = UUID.randomUUID().toString();
        store.stage(7, "session-a", requestId, "我学过一点 Java", null);
    }

    @Test
    void completedAnswerIsPersistedBeforeTheWholeRequestIsSealed() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(reply("好"), reply("的")));
        var output = send(true).collectList().block(Duration.ofSeconds(5));
        assertThat(output).filteredOn(text -> !text.isEmpty()).containsExactly("好", "的");
        assertThat(repository.findByConversationId("7_session-a")).extracting(Message::getText).containsExactly("我学过一点 Java", "好的");
        assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task", String.class)).isEqualTo("PERSISTED");
        assertThat(store.claimDue(1, Duration.ZERO)).isNull();
        assertThat(store.markReady(requestId, "7_session-a")).isTrue();
        assertThat(store.claimDue(1, Duration.ZERO)).isNotNull();
    }

    @Test
    void cancellationFailureAndLostOwnershipNeverMakeTaskReady() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(reply("部分")), Flux.never()));
        assertThat(send(true).take(1).collectList().block(Duration.ofSeconds(5))).containsExactly("部分");
        assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task", String.class)).isEqualTo("WAITING");
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(reply("部分")), Flux.error(new IllegalStateException("failed"))));
        assertThatThrownBy(() -> send(true).collectList().block(Duration.ofSeconds(5))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task", String.class)).isEqualTo("WAITING");
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(reply("结束")));
        send(false).collectList().block(Duration.ofSeconds(5));
        assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task", String.class)).isEqualTo("WAITING");
    }

    @Test
    void handoffAndCourseMetadataCanShareTheFinalAnswer() {
        var recommendations = new CourseRecommendations();
        recommendations.nextIntakeReply();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(reply("回答")));
        client.prompt().system("test").user("我学过一点 Java")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "7_session-a"))
                .advisors(new CourseCardMetadataAdvisor(recommendations, () -> true))
                .advisors(new UserMemoryCompletionAdvisor(requestId, () -> true))
                .stream().content().collectList().block(Duration.ofSeconds(5));
        assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task", String.class)).isEqualTo("PERSISTED");
        var saved = repository.findByConversationId("7_session-a");
        assertThat(saved.get(saved.size() - 1).getMetadata()).containsKey(CourseRecommendations.INTAKE_STATE);
    }

    @Test
    void handoffFailureDoesNotLoseTheCompletedChat() {
        jdbc.execute("DROP TABLE user_memory_task");
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(reply("回答")));
        send(true).collectList().block(Duration.ofSeconds(5));
        assertThat(repository.findByConversationId("7_session-a")).extracting(Message::getText)
                .containsExactly("我学过一点 Java", "回答");
    }

    @Test
    void completedConversationCanBeExtractedAndLoadedInAnotherSessionForTheSameUser() {
        var service = new UserMemoryService(store, chatMemory);
        var first = service.prepare(7, "session-a", requestId, "7_session-a", "我学过一点 Java");
        assertThat(first.staged()).isTrue();
        assertThat(first.memories()).isEmpty();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(reply("好的")));
        send(true).collectList().block(Duration.ofSeconds(5));
        assertThat(store.markReady(requestId, "7_session-a")).isTrue();
        var batch = store.claimDue(1, Duration.ZERO);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(reply("{\"memories\":[{\"key\":\"background\","
                + "\"content\":\"学过一点 Java\",\"requestId\":\"" + requestId + "\",\"evidence\":\"学过一点 Java\"}]}")));
        assertThat(store.apply(batch, new UserMemoryExtractor(model).extract(batch))).isTrue();
        var next = service.prepare(7, "session-b", UUID.randomUUID().toString(), "7_session-b", "继续帮我选课");
        assertThat(next.memories()).extracting(UserMemoryStore.Memory::content).containsExactly("学过一点 Java");
        var other = service.prepare(8, "session-c", UUID.randomUUID().toString(), "8_session-c", "继续帮我选课");
        assertThat(other.memories()).isEmpty();
        assertThat(chatMemory.get("7_session-b")).isEmpty();
    }

    private Flux<String> send(boolean active) {
        return client.prompt().system("test").user("我学过一点 Java")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "7_session-a"))
                .advisors(new UserMemoryCompletionAdvisor(requestId, () -> active))
                .stream().content();
    }

    private static ChatResponse reply(String value) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));
    }
}
