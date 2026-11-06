package com.tianji.aigc;

import com.tianji.aigc.controller.ChatController;
import com.tianji.aigc.controller.SessionController;
import com.tianji.aigc.dto.ChatDTO;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.mapper.ChatSessionMapper;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.config.name=aigc-history-test", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:aigc_history;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=never",
        "spring.ai.openai.api-key=test-only", "spring.ai.openai.base-url=http://127.0.0.1:1",
        "spring.ai.openai.chat.options.model=test-model", "tj.auth.resource.enable=false",
        "tj.ai.knowledge.rag.mode=A", "tj.ai.knowledge.enabled=true", "tj.ai.knowledge.public-bases.tianji-points[0]=tianji-points-guide",
        "tj.ai.knowledge.index-name=tj_aigc_test", "tj.ai.knowledge.embedding.model=test-embedding",
        "spring.ai.model.embedding=none",
        "tj.ai.session.examples[0].title=积分", "tj.ai.session.examples[0].describe=积分规则"
})
class ChatSessionHistoryTest {
    @Autowired ChatSessionMapper mapper;
    @Autowired ChatSessionService service;
    @Autowired JdbcTemplate jdbc;
    @MockBean ChatMemory memory;
    @MockBean com.tianji.aigc.service.ChatGenerationStore generations;
    @MockBean org.springframework.ai.vectorstore.VectorStore vectorStore;
    @MockBean com.tianji.aigc.knowledge.KnowledgeDocumentIndex documentIndex;
    @MockBean org.springframework.ai.embedding.EmbeddingModel embeddingModel;
    @MockBean ChatService chat;
    @MockBean com.tianji.aigc.config.SystemPromptConfig prompts;

    @BeforeEach
    void prepare() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_session (id BIGINT PRIMARY KEY, session_id VARCHAR(32), "
                + "user_id BIGINT, title VARCHAR(128), title_status TINYINT NOT NULL DEFAULT 2, knowledge_base_id VARCHAR(64), create_time TIMESTAMP, update_time TIMESTAMP, creater BIGINT, updater BIGINT)");
        jdbc.update("DELETE FROM chat_session");
        UserContext.setUser(42L);
    }

    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    private ChatSession insert(String sessionId, long user, int hoursAgo) {
        var session = ChatSession.builder().sessionId(sessionId).userId(user)
                .createTime(LocalDateTime.now().minusHours(hoursAgo))
                .updateTime(LocalDateTime.now().minusHours(hoursAgo)).build();
        mapper.insert(session);
        return session;
    }

    @Test
    void creationPersistsKnowledgeScopeAndFollowupsCannotChangeIt() {
        var created = service.createSession(1, "tianji-points");
        assertThat(created.getKnowledgeBaseId()).isEqualTo("tianji-points");
        assertThat(service.requireOwnedSession(created.getSessionId()).getKnowledgeBaseId()).isEqualTo("tianji-points");
        service.prepareChat(created.getSessionId(), "签到第七天多少分？");
        service.prepareChat(created.getSessionId(), "为什么？");
        assertThat(service.requireOwnedSession(created.getSessionId()).getKnowledgeBaseId()).isEqualTo("tianji-points");
        assertThat(service.queryHistory(new PageQuery()).getList().get(0).getKnowledgeBaseId()).isEqualTo("tianji-points");
    }

    @Test
    void omittedKnowledgeScopeCreatesOrdinarySession() {
        var created = service.createSession(1);
        assertThat(created.getKnowledgeBaseId()).isNull();
        assertThat(service.requireOwnedSession(created.getSessionId()).getKnowledgeBaseId()).isNull();
    }

    @Test
    void unknownEmptyAndInjectedKnowledgeScopesCannotCreateSessions() {
        for (String knowledge : List.of("private-other-course", "", "tianji-points' or true")) {
            assertThatThrownBy(() -> service.createSession(1, knowledge)).isInstanceOf(BadRequestException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_session", Integer.class)).isZero();
    }

    @Test
    void historyIsPagedSortedAndRestrictedToCurrentUser() {
        insert("old", 42L, 3);
        insert("new", 42L, 1);
        insert("other-user", 43L, 0);
        var result = service.queryHistory(new PageQuery().setPageSize(1).setSortBy("user_id"));
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getList()).extracting(item -> item.getSessionId()).containsExactly("new");
        var second = service.queryHistory(new PageQuery().setPageNo(2).setPageSize(1));
        assertThat(second.getList()).extracting(item -> item.getSessionId()).containsExactly("old");
    }

    @Test
    void firstQuestionBecomesTitleAndContinuingKeepsIt() {
        var session = insert("owned", 42L, 2);
        service.prepareChat("owned", "  查询 Java 课程  ");
        var saved = mapper.selectById(session.getId());
        assertThat(saved.getTitle()).isEqualTo("查询 Java 课程");
        assertThat(saved.getUpdateTime()).isAfter(session.getUpdateTime());
        service.prepareChat("owned", "适合零基础吗？");
        assertThat(mapper.selectById(session.getId()).getTitle()).isEqualTo(saved.getTitle());
    }

    @Test
    void foreignAndMissingSessionsCannotReadChatOrStop() {
        insert("foreign", 43L, 0);
        var sessions = new SessionController(service);
        var chats = new ChatController(chat, service, mock(com.tianji.aigc.service.BotTextService.class));
        for (String id : List.of("foreign", "missing")) {
            assertThatThrownBy(() -> sessions.queryBySessionId(id)).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> chats.chat(ChatDTO.builder().sessionId(id).question("继续").build()))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> chats.stop(id)).isInstanceOf(BadRequestException.class);
        }
        verifyNoInteractions(memory, chat);
    }

    @Test
    void serviceRejectsForeignAndMissingSessionsBeforeReadingMemory() {
        insert("foreign", 43L, 0);
        for (String id : List.of("foreign", "missing")) {
            assertThatThrownBy(() -> service.queryBySessionId(id))
                    .isInstanceOf(BadRequestException.class);
        }
        verifyNoInteractions(memory);
    }

    @Test
    void ownedSessionHistoryExposesCardAndStoppedMetadata() {
        insert("owned", 42L, 1);
        when(memory.get("42_owned")).thenReturn(List.of(AssistantMessage.builder().content("课程信息")
                .properties(Map.of(MessageUtil.COURSE_CARDS, List.of(Map.of("courseId", "9007199254740993")),
                        MessageUtil.GENERATION_STATUS, MessageUtil.STOPPED)).build()));
        var result = new SessionController(service).queryBySessionId("owned");
        assertThat(result.get(0).getParams()).containsKeys(MessageUtil.COURSE_CARDS, MessageUtil.GENERATION_STATUS);
        verify(memory).get("42_owned");
    }

    @Test
    void anonymousUserCannotListCreateOrOpenSessions() {
        UserContext.removeUser();
        assertThatThrownBy(() -> service.queryHistory(new PageQuery())).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.createSession(3)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.requireOwnedSession("owned")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void manualTitleIsTrimmedAndSurvivesFurtherQuestions() {
        var owned = insert("owned", 42L, 2);
        var activityTime = mapper.selectById(owned.getId()).getUpdateTime();
        service.renameSession("owned", "  Java 学习计划  ");
        assertThat(mapper.selectById(owned.getId()).getTitle()).isEqualTo("Java 学习计划");
        // 改名不影响会话的聊天活动时间和排序。
        assertThat(mapper.selectById(owned.getId()).getUpdateTime()).isEqualTo(activityTime);
        service.prepareChat("owned", "后续提问");
        assertThat(mapper.selectById(owned.getId()).getTitle()).isEqualTo("Java 学习计划");
        service.renameSession("owned", "字".repeat(40));
        assertThat(mapper.selectById(owned.getId()).getTitle()).hasSize(40);
    }

    @Test
    void blankAndOverlongTitlesDoNotChangeStoredTitle() {
        var owned = insert("owned", 42L, 0);
        service.renameSession("owned", "原来的名字");
        for (String title : new String[]{null, "", "  \t\n", "字".repeat(41)}) {
            assertThatThrownBy(() -> service.renameSession("owned", title)).isInstanceOf(BadRequestException.class);
        }
        assertThat(mapper.selectById(owned.getId()).getTitle()).isEqualTo("原来的名字");
    }

    @Test
    void foreignMissingAndAnonymousSessionsCannotBeMutated() {
        insert("foreign", 43L, 0);
        for (String id : List.of("foreign", "missing")) {
            assertThatThrownBy(() -> service.renameSession(id, "改名")).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.deleteSession(id)).isInstanceOf(BadRequestException.class);
        }
        UserContext.removeUser();
        assertThatThrownBy(() -> service.renameSession("foreign", "改名")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.deleteSession("foreign")).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(generations, memory);
    }

    @Test
    void deletingRemovesOnlyOwnedSessionAndItsMemoryBeforeReleasingSlot() {
        var owned = insert("owned", 42L, 0);
        var other = insert("other", 42L, 0);
        var lease = mock(com.tianji.aigc.service.ChatGenerationStore.Generation.class);
        when(generations.acquire("owned")).thenReturn(lease);
        doAnswer(invocation -> {
            assertThat(mapper.selectById(owned.getId())).isNull();
            return null;
        }).when(generations).release(lease);
        service.deleteSession("owned");
        assertThat(mapper.selectById(owned.getId())).isNull();
        assertThat(mapper.selectById(other.getId())).isNotNull();
        var order = inOrder(generations, memory);
        order.verify(generations).acquire("owned");
        order.verify(memory).clear("42_owned");
        order.verify(generations).release(lease);
        assertThatThrownBy(() -> service.requireOwnedSession("owned")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void generatingSessionCannotBeDeleted() {
        var owned = insert("owned", 42L, 0);
        when(generations.acquire("owned")).thenThrow(new BadRequestException("当前会话正在生成，请稍后再试"));
        assertThatThrownBy(() -> service.deleteSession("owned")).isInstanceOf(BadRequestException.class);
        assertThat(mapper.selectById(owned.getId())).isNotNull();
        verifyNoInteractions(memory);
    }

    @Test
    void memoryFailureKeepsSessionAndReleasesSlot() {
        var owned = insert("owned", 42L, 0);
        var lease = mock(com.tianji.aigc.service.ChatGenerationStore.Generation.class);
        when(generations.acquire("owned")).thenReturn(lease);
        doThrow(new IllegalStateException("storage unavailable")).when(memory).clear("42_owned");
        assertThatThrownBy(() -> service.deleteSession("owned")).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.selectById(owned.getId())).isNotNull();
        verify(generations).release(lease);
    }

    @Test
    void sqlDeletionFailureRollsBackMessageClearAndKeepsSession() {
        var owned = insert("owned", 42L, 0);
        jdbc.execute("CREATE TABLE session_delete_messages (conversation_id VARCHAR(128), body VARCHAR(128))");
        jdbc.execute("CREATE TABLE session_delete_reference (session_id BIGINT REFERENCES chat_session(id))");
        try {
            jdbc.update("INSERT INTO session_delete_messages VALUES ('42_owned', '原消息')");
            jdbc.update("INSERT INTO session_delete_reference VALUES (?)", owned.getId());
            doAnswer(invocation -> {
                jdbc.update("DELETE FROM session_delete_messages WHERE conversation_id = ?", invocation.getArgument(0, String.class));
                return null;
            }).when(memory).clear("42_owned");
            var lease = mock(com.tianji.aigc.service.ChatGenerationStore.Generation.class);
            when(generations.acquire("owned")).thenReturn(lease);
            assertThatThrownBy(() -> service.deleteSession("owned"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(mapper.selectById(owned.getId())).isNotNull();
            assertThat(jdbc.queryForObject("SELECT body FROM session_delete_messages", String.class)).isEqualTo("原消息");
            verify(generations).release(lease);
        } finally {
            jdbc.execute("DROP TABLE session_delete_reference");
            jdbc.execute("DROP TABLE session_delete_messages");
        }
    }

    @Test
    void mongoDeleteSqlFailureKeepsMessagesAndDoesNotLeaveCleanupTask() {
        var owned = insert("owned", 42L, 0);
        jdbc.execute("CREATE TABLE chat_memory_cleanup (store_id CHAR(64), conversation_id VARCHAR(128), last_attempt_at TIMESTAMP NULL, PRIMARY KEY (store_id, conversation_id))");
        jdbc.execute("CREATE TABLE session_delete_reference (session_id BIGINT REFERENCES chat_session(id))");
        var cleanup = new com.tianji.aigc.memory.MongoMemoryCleanup(jdbc, memory, "mongodb://localhost/fixture");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mongoMemoryCleanup", cleanup);
        try {
            jdbc.update("INSERT INTO session_delete_reference VALUES (?)", owned.getId());
            var lease = mock(com.tianji.aigc.service.ChatGenerationStore.Generation.class);
            when(generations.acquire("owned")).thenReturn(lease);
            assertThatThrownBy(() -> service.deleteSession("owned"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(mapper.selectById(owned.getId())).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_cleanup", Integer.class)).isZero();
            verify(memory, never()).clear(anyString());
            verify(generations).release(lease);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "mongoMemoryCleanup", null);
            jdbc.execute("DROP TABLE session_delete_reference");
            jdbc.execute("DROP TABLE chat_memory_cleanup");
        }
    }

    @Test
    void mongoCleanupIsDurableAndRetriesAfterFailure() {
        var owned = insert("owned", 42L, 0);
        jdbc.execute("CREATE TABLE chat_memory_cleanup (store_id CHAR(64), conversation_id VARCHAR(128), last_attempt_at TIMESTAMP NULL, PRIMARY KEY (store_id, conversation_id))");
        var cleanup = new com.tianji.aigc.memory.MongoMemoryCleanup(jdbc, memory, "mongodb://localhost/fixture");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mongoMemoryCleanup", cleanup);
        try {
            var lease = mock(com.tianji.aigc.service.ChatGenerationStore.Generation.class);
            when(generations.acquire("owned")).thenReturn(lease);
            service.deleteSession("owned");
            assertThat(mapper.selectById(owned.getId())).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_cleanup", Integer.class)).isEqualTo(1);
            verify(memory, never()).clear(anyString());
            doThrow(new IllegalStateException("Mongo unavailable")).doNothing().when(memory).clear("42_owned");
            cleanup.retryPending();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_cleanup", Integer.class)).isEqualTo(1);
            new com.tianji.aigc.memory.MongoMemoryCleanup(jdbc, memory, "mongodb://localhost/fixture").retryPending();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_cleanup", Integer.class)).isZero();
            verify(memory, times(2)).clear("42_owned");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "mongoMemoryCleanup", null);
            jdbc.execute("DROP TABLE chat_memory_cleanup");
        }
    }

    @Test
    void mongoCleanupTargetsTheOriginalStoreAndSurvivesCredentialRotation() {
        jdbc.execute("CREATE TABLE chat_memory_cleanup (store_id CHAR(64), conversation_id VARCHAR(128), last_attempt_at TIMESTAMP NULL, PRIMARY KEY (store_id, conversation_id))");
        try {
            new com.tianji.aigc.memory.MongoMemoryCleanup(jdbc, memory, "mongodb://user:old@host1/fixture").enqueue("42_owned");
            new com.tianji.aigc.memory.MongoMemoryCleanup(jdbc, memory, "mongodb://user:new@host2/fixture").retryPending();
            verify(memory, never()).clear(anyString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_cleanup", Integer.class)).isEqualTo(1);
            new com.tianji.aigc.memory.MongoMemoryCleanup(jdbc, memory, "mongodb://user:new@host1/fixture").retryPending();
            verify(memory).clear("42_owned");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_cleanup", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TABLE chat_memory_cleanup");
        }
    }

    @Test
    void onlyFirstTurnCanClaimAutomaticTitleAndSavingKeepsActivityTime() {
        var created = service.createSession(1);
        service.prepareChat(created.getSessionId(), "这个怎么实现？");
        var before = service.requireOwnedSession(created.getSessionId());
        assertThat(service.claimFirstTurnTitle(created.getSessionId(), 42L)).isTrue();
        assertThat(service.claimFirstTurnTitle(created.getSessionId(), 42L)).isFalse();
        service.saveGeneratedTitle(created.getSessionId(), 42L, "Redis 分布式锁的实现");
        var saved = service.requireOwnedSession(created.getSessionId());
        assertThat(saved.getTitle()).isEqualTo("Redis 分布式锁的实现");
        assertThat(saved.getTitleStatus()).isEqualTo(2);
        assertThat(saved.getUpdateTime()).isEqualTo(before.getUpdateTime());
        service.prepareChat(created.getSessionId(), "再讲讲");
        assertThat(service.claimFirstTurnTitle(created.getSessionId(), 42L)).isFalse();
    }

    @Test
    void manualRenameToSameTemporaryTitleStillRejectsLateAutomaticTitle() {
        var created = service.createSession(1);
        service.prepareChat(created.getSessionId(), "临时标题");
        assertThat(service.claimFirstTurnTitle(created.getSessionId(), 42L)).isTrue();
        service.renameSession(created.getSessionId(), "临时标题");
        service.saveGeneratedTitle(created.getSessionId(), 42L, "迟到的自动标题");
        var saved = service.requireOwnedSession(created.getSessionId());
        assertThat(saved.getTitle()).isEqualTo("临时标题");
        assertThat(saved.getTitleStatus()).isEqualTo(3);
    }

    @Test
    void legacySessionsAndOtherUsersAreExcludedFromAutomaticTitle() {
        var legacy = insert("legacy", 42L, 0);
        assertThat(service.claimFirstTurnTitle("legacy", 42L)).isFalse();
        assertThat(mapper.selectById(legacy.getId()).getTitleStatus()).isEqualTo(2);
        var created = service.createSession(1);
        assertThat(service.claimFirstTurnTitle(created.getSessionId(), 43L)).isFalse();
        service.saveGeneratedTitle(created.getSessionId(), 43L, "其他用户");
        assertThat(service.requireOwnedSession(created.getSessionId()).getTitleStatus()).isZero();
        assertThat(service.claimFirstTurnTitle(created.getSessionId(), 42L)).isTrue();
        service.renameSession(created.getSessionId(), "手动标题");
        assertThat(service.claimFirstTurnTitle(created.getSessionId(), 42L)).isFalse();
    }

    @Test
    void mutationEndpointsBindTitleAndSessionId() throws Exception {
        var delegate = mock(ChatSessionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SessionController(delegate)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/session/owned/title")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Java 学习计划\"}")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/session/owned"))
                .andExpect(status().isOk());
        verify(delegate).renameSession("owned", "Java 学习计划");
        verify(delegate).deleteSession("owned");
    }

    @Test
    void listEndpointRejectsInvalidPagingBeforeQuery() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SessionController(service)).build();
        mvc.perform(get("/session").param("pageNo", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/session").param("pageSize", "0")).andExpect(status().isBadRequest());
    }
}
