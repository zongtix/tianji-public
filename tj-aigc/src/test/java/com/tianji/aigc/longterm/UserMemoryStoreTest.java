package com.tianji.aigc.longterm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserMemoryStoreTest {
    private JdbcTemplate jdbc;
    private UserMemoryStore store;
    private UserMemoryCipher cipher;

    @ParameterizedTest
    @ValueSource(strings = {"联系号码是 138 0000 0000", "联系号码是 138-0000-0000",
            "联系方式是幺三八零零零零零零零零", "联系方式是一三八 零零零零 零零零零"})
    void expandedPhoneFormatsAreRejectedBeforeQueueing(String syntheticQuestion) {
        assertThat(store.stage(7, "session-a", UUID.randomUUID().toString(), syntheticQuestion, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isZero();
    }

    @Test
    void expandedPhoneInPreviousQuestionIsNotSentWithSafeAnswer() {
        String request=UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", request, "我想学习 Java", "联系号码是 138 0000 0000？")).isTrue();
        assertThat(makeReady(request, "7_session-a")).isTrue();
        assertThat(store.claimDue(1, Duration.ZERO).turns().get(0).previousQuestion()).isNull();
    }

    @Test
    void ordinaryLearningScheduleIsStillAccepted() {
        assertThat(store.stage(7, "session-a", UUID.randomUUID().toString(), "我每周学习 1-3 小时", null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"密码是 synthetic-test", "邮箱是 student@example.test", "银行卡 6222021234567890123",
            "API key 是 synthetic-test", "联系方式是 138 0000 0000"})
    void sensitiveSyntheticQuestionsNeverEnterTheQueue(String question) {
        assertThat(store.stage(7, "session-a", UUID.randomUUID().toString(), question, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"我每周能学 1-3 小时", "我学过 Java 17", "我做过两个 Python 小项目"})
    void ordinarySyntheticLearningStatementsCanEnterTheQueue(String question) {
        assertThat(store.stage(7, "session-a", UUID.randomUUID().toString(), question, null)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isEqualTo(1);
    }

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE user_memory_profile (user_id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL, epoch BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE user_memory_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, memory_key VARCHAR(32) NOT NULL, content_cipher TEXT NOT NULL, version BIGINT NOT NULL, source_task_id BIGINT NOT NULL, source_session_id VARCHAR(32) NOT NULL, source_request_id VARCHAR(36) NOT NULL, updated_at TIMESTAMP NOT NULL, INDEX idx_memory_user_key(user_id, memory_key, id))");
        jdbc.execute("CREATE TABLE user_memory_task (id BIGINT AUTO_INCREMENT PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE, user_id BIGINT NOT NULL, session_id VARCHAR(32) NOT NULL, epoch BIGINT NOT NULL, state VARCHAR(16) NOT NULL, payload_cipher TEXT NOT NULL, created_at TIMESTAMP NOT NULL, ready_at TIMESTAMP, claim_token VARCHAR(36), claim_until TIMESTAMP, attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP)");
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        cipher = new UserMemoryCipher(key);
        store = new UserMemoryStore(jdbc, new DataSourceTransactionManager(source), cipher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payload", "json"})
    void undecodableClaimPersistsRetriesAndDoesNotBlockAnotherUser(String corruption) {
        String request = UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", request, "我想学后端", null)).isTrue();
        assertThat(makeReady(request, "7_session-a")).isTrue();
        String payload = corruption.equals("payload") ? "broken-payload" : cipher.encrypt(7, "{invalid-json");
        jdbc.update("UPDATE user_memory_task SET payload_cipher=? WHERE request_id=?", payload, request);
        String other = UUID.randomUUID().toString();
        assertThat(store.stage(8, "session-b", other, "我想学 Java", null)).isTrue();
        assertThat(makeReady(other, "8_session-b")).isTrue();

        for (int attempt = 1; attempt <= 3; attempt++) {
            LocalDateTime started = LocalDateTime.now();
            assertThatThrownBy(() -> store.claimDue(1, Duration.ZERO))
                    .isInstanceOf(IllegalStateException.class).hasMessage("无法读取待处理轮次").hasNoCause();
            assertThat(jdbc.queryForObject("SELECT attempts FROM user_memory_task WHERE request_id=?", Integer.class, request))
                    .isEqualTo(attempt);
            assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task WHERE request_id=?", String.class, request))
                    .isEqualTo(attempt < 3 ? "READY" : "FAILED");
            assertThat(jdbc.queryForObject("SELECT claim_token FROM user_memory_task WHERE request_id=?", String.class, request)).isNull();
            assertThat(jdbc.queryForObject("SELECT claim_until FROM user_memory_task WHERE request_id=?", LocalDateTime.class, request)).isNull();
            LocalDateTime next = jdbc.queryForObject("SELECT next_attempt_at FROM user_memory_task WHERE request_id=?", LocalDateTime.class, request);
            assertThat(next).isAfterOrEqualTo(started.plus(attempt < 3 ? Duration.ofSeconds(10L * attempt) : Duration.ofHours(24)));
            if (attempt == 1) {
                var batch = store.claimDue(1, Duration.ZERO);
                assertThat(batch.userId()).isEqualTo(8);
                assertThat(batch.turns()).extracting(UserMemoryStore.Turn::requestId).containsExactly(other);
                assertThat(store.apply(batch, List.of(new UserMemoryStore.Candidate("goal", "想学 Java", other, "想学 Java")))).isTrue();
            } else {
                assertThat(store.claimDue(1, Duration.ZERO)).isNull();
            }
            if (attempt < 3) jdbc.update("UPDATE user_memory_task SET next_attempt_at=? WHERE request_id=?", LocalDateTime.now().minusSeconds(1), request);
        }
        assertThat(store.list(8)).extracting(UserMemoryStore.Memory::content).containsExactly("想学 Java");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void damagedMemoryRowDoesNotHideHealthyRowsOrBlockClaimAndIsNotDeleted(org.springframework.boot.test.system.CapturedOutput output) {
        var damaged = remember(7, "background", "学过 Java");
        var healthy = remember(7, "goal", "想学后端");
        String invalid = "PRIVATE_BROKEN_CIPHER";
        jdbc.update("UPDATE user_memory_item SET content_cipher=? WHERE id=?", invalid, damaged.id());
        assertThat(store.list(7)).containsExactly(healthy);
        assertThat(store.state(7).memories()).containsExactly(healthy);
        String request = UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", request, "我喜欢项目练习", null)).isTrue();
        assertThat(makeReady(request, "7_session-a")).isTrue();
        var batch = store.claimDue(1, Duration.ZERO);
        assertThat(batch.snapshot()).containsExactly(healthy);
        assertThat(batch.attempts()).isEqualTo(1);
        assertThat(store.apply(batch, List.of(new UserMemoryStore.Candidate("preference", "喜欢项目练习", request, "喜欢项目练习")))).isTrue();
        assertThat(jdbc.queryForObject("SELECT content_cipher FROM user_memory_item WHERE id=?", String.class, damaged.id())).isEqualTo(invalid);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_item WHERE user_id=7", Integer.class)).isEqualTo(3);
        assertThat(output.getAll()).contains("memoryId=" + damaged.id()).doesNotContain(invalid, "学过 Java", "想学后端");
    }

    @Test
    void unreadableRowsStillCountTowardsThePersistedMemoryLimit() {
        for (int i = 0; i < UserMemoryStore.MAX_MEMORIES; i++) {
            jdbc.update("INSERT INTO user_memory_item (user_id,memory_key,content_cipher,version,source_task_id,source_session_id,source_request_id,updated_at) VALUES (7,'background',?,1,0,'session-a',?,?)",
                    "broken-cipher-" + i, UUID.randomUUID().toString(), LocalDateTime.now());
        }
        String request = UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", request, "我想学 Java", null)).isTrue();
        assertThat(makeReady(request, "7_session-a")).isTrue();
        var batch = store.claimDue(1, Duration.ZERO);
        assertThat(batch.snapshot()).isEmpty();
        assertThat(store.apply(batch, List.of(new UserMemoryStore.Candidate("goal", "想学 Java", request, "想学 Java")))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_item WHERE user_id=7", Integer.class))
                .isEqualTo(UserMemoryStore.MAX_MEMORIES);
    }

    @Test
    void abandonDeletesOnlyUnsealedTasksAndLeavesReadyWorkClaimable() {
        String waiting = UUID.randomUUID().toString(), persisted = UUID.randomUUID().toString(), ready = UUID.randomUUID().toString();
        for (String request : List.of(waiting, persisted, ready)) assertThat(store.stage(7, "session-a", request, "我想学 Java", null)).isTrue();
        assertThat(store.markPersisted(persisted, "7_session-a")).isTrue();
        assertThat(makeReady(ready, "7_session-a")).isTrue();
        for (String request : List.of(waiting, persisted, ready)) store.abandon(request);
        assertThat(jdbc.queryForList("SELECT request_id FROM user_memory_task", String.class)).containsExactly(ready);
        assertThat(store.claimDue(1, Duration.ZERO).turns()).extracting(UserMemoryStore.Turn::requestId).containsExactly(ready);
    }

    @Test
    void payloadAndSnapshotAreDecodedAfterTheClaimCommits() {
        var memory = remember(7, "background", "学过 Java");
        String request = UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", request, "我想学后端", "学过什么？")).isTrue();
        assertThat(makeReady(request, "7_session-a")).isTrue();
        var observedCipher = spy(cipher);
        store = new UserMemoryStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), observedCipher);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task WHERE request_id=?", String.class, request)).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("SELECT attempts FROM user_memory_task WHERE request_id=?", Integer.class, request)).isEqualTo(1);
            return invocation.callRealMethod();
        }).when(observedCipher).decrypt(eq(7L), anyString());

        var batch = store.claimDue(1, Duration.ZERO);
        assertThat(batch.turns()).extracting(UserMemoryStore.Turn::question).containsExactly("我想学后端");
        assertThat(batch.turns()).extracting(UserMemoryStore.Turn::previousQuestion).containsExactly("学过什么？");
        assertThat(batch.snapshot()).containsExactly(memory);
        assertThat(batch.attempts()).isEqualTo(1);
        verify(observedCipher, times(2)).decrypt(eq(7L), anyString());
    }

    @Test
    void badFirstPayloadUsesTheMaximumAttemptCountOfTheWholeClaim() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", first, "我想学后端", null)).isTrue();
        assertThat(makeReady(first, "7_session-a")).isTrue();
        assertThat(store.stage(7, "session-a", second, "我想学 Java", null)).isTrue();
        assertThat(makeReady(second, "7_session-a")).isTrue();
        jdbc.update("UPDATE user_memory_task SET payload_cipher=? WHERE request_id=?", "broken-payload", first);
        jdbc.update("UPDATE user_memory_task SET attempts=2 WHERE request_id=?", second);

        assertThatThrownBy(() -> store.claimDue(1, Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("SELECT attempts FROM user_memory_task ORDER BY id", Integer.class)).containsExactly(1, 3);
        assertThat(jdbc.queryForList("SELECT state FROM user_memory_task ORDER BY id", String.class)).containsExactly("FAILED", "FAILED");
        assertThat(store.claimDue(1, Duration.ZERO)).isNull();
    }

    @Test
    void newBackgroundInAnotherSessionPreservesEarlierExperience() {
        String first = UUID.randomUUID().toString();
        store.stage(7, "session-a", first, "我有 Java 基础，看过微服务", null);
        makeReady(first, "7_session-a");
        store.apply(store.claimDue(1, Duration.ZERO), List.of(
                new UserMemoryStore.Candidate("background", "有 Java 基础，看过微服务", first, "有 Java 基础，看过微服务")));
        var original = store.list(7).get(0);

        String second = UUID.randomUUID().toString();
        store.stage(7, "session-b", second, "我还学过 Python", null);
        makeReady(second, "7_session-b");
        assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(
                new UserMemoryStore.Candidate("background", "学过 Python", second, "学过 Python")))).isTrue();

        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content)
                .containsExactly("有 Java 基础，看过微服务", "学过 Python");
        assertThat(store.list(7).get(0)).isEqualTo(original);
        assertThat(store.context(7)).hasSize(2);
        assertThat(store.list(8)).isEmpty();
    }

    @Test
    void oneBatchCanKeepSeveralFactsInTheSameCategory() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        store.stage(7, "session-a", first, "我有 Java 基础，看过微服务", null);
        makeReady(first, "7_session-a");
        store.stage(7, "session-a", second, "我还学过 Python", null);
        makeReady(second, "7_session-a");
        var batch = store.claimDue(1, Duration.ZERO);
        assertThat(store.apply(batch, List.of(
                new UserMemoryStore.Candidate("background", "有 Java 基础", first, "有 Java 基础"),
                new UserMemoryStore.Candidate("background", "看过微服务", first, "看过微服务"),
                new UserMemoryStore.Candidate("background", "学过 Python", second, "学过 Python")))).isTrue();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content)
                .containsExactly("有 Java 基础", "看过微服务", "学过 Python");
    }

    @Test
    void repeatedFactDoesNotCreateAnotherRowOrChangeItsVersion() {
        for (int i = 0; i < 2; i++) {
            String request = UUID.randomUUID().toString();
            store.stage(7, "session-a", request, "我学过 Python", null);
            makeReady(request, "7_session-a");
            assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(
                    new UserMemoryStore.Candidate("background", "学过 Python", request, "学过 Python")))).isTrue();
        }
        assertThat(store.list(7)).hasSize(1);
        assertThat(store.list(7).get(0).version()).isEqualTo(1);
    }

    @Test
    void whitespaceAndCaseVariantsAreDeduplicatedWithinABatch() {
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-a", request, "我学过 Python，也学过 python", null);
        makeReady(request, "7_session-a");
        assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(
                new UserMemoryStore.Candidate("background", "学过 Python", request, "学过 Python"),
                new UserMemoryStore.Candidate("background", "学过python", request, "学过 python")))).isTrue();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("学过 Python");
    }

    @Test
    void explicitCorrectionChangesOnlyTheTargetFact() {
        String first = UUID.randomUUID().toString();
        store.stage(7, "session-a", first, "我学过 Java，也学过 Python", null);
        makeReady(first, "7_session-a");
        store.apply(store.claimDue(1, Duration.ZERO), List.of(
                new UserMemoryStore.Candidate("background", "学过 Java", first, "学过 Java"),
                new UserMemoryStore.Candidate("background", "学过 Python", first, "学过 Python")));
        var java = store.list(7).get(0);
        var python = store.list(7).get(1);

        String correction = UUID.randomUUID().toString();
        store.stage(7, "session-b", correction, "之前说错了，我没学过 Java", null);
        makeReady(correction, "7_session-b");
        assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(
                new UserMemoryStore.Candidate("background", "没学过 Java", correction, "没学过 Java", Long.toString(java.id()))))).isTrue();

        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("没学过 Java", "学过 Python");
        assertThat(store.list(7).get(0).id()).isEqualTo(java.id());
        assertThat(store.list(7).get(0).version()).isEqualTo(java.version() + 1);
        assertThat(store.list(7).get(1)).isEqualTo(python);
    }

    @Test
    void missingForeignAndWrongCategoryTargetsNeverBecomeAdditions() {
        var own = remember(7, "background", "学过 Java");
        var foreign = remember(8, "background", "学过 Python");
        for (String target : List.of("invalid", "999999", Long.toString(foreign.id()), Long.toString(own.id()))) {
            String request = UUID.randomUUID().toString();
            store.stage(7, "session-a", request, "我想学后端", null);
            makeReady(request, "7_session-a");
            assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(
                    new UserMemoryStore.Candidate("goal", "想学后端", request, "想学后端", target)))).isTrue();
        }
        assertThat(store.list(7)).containsExactly(own);
        assertThat(store.list(8)).containsExactly(foreign);
    }

    @Test
    void concurrentAdditionsReextractAgainstTheNewSnapshotWithoutLosingEitherFact() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        store.stage(7, "session-a", first, "我学过 Java", null);
        makeReady(first, "7_session-a");
        var older = store.claimDue(1, Duration.ZERO);
        store.stage(7, "session-b", second, "我学过 Python", null);
        makeReady(second, "7_session-b");
        var newer = store.claimDue(1, Duration.ZERO);
        assertThat(store.apply(newer, List.of(new UserMemoryStore.Candidate("background", "学过 Python", second, "学过 Python")))).isTrue();
        var java = new UserMemoryStore.Candidate("background", "学过 Java", first, "学过 Java");
        assertThat(store.apply(older, List.of(java))).isFalse();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("学过 Python");
        jdbc.update("UPDATE user_memory_task SET next_attempt_at=NULL WHERE state='READY'");
        var retried = store.claimDue(1, Duration.ZERO);
        assertThat(retried.snapshot()).extracting(UserMemoryStore.Memory::content).containsExactly("学过 Python");
        assertThat(store.apply(retried, List.of(java))).isTrue();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("学过 Python", "学过 Java");
    }

    @Test
    void olderCorrectionCannotOverwriteANewerCorrectionOfTheSameFact() {
        var original = remember(7, "goal", "想学前端");
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        store.stage(7, "session-a", first, "我改为想学后端", null);
        makeReady(first, "7_session-a");
        var older = store.claimDue(1, Duration.ZERO);
        store.stage(7, "session-b", second, "我改为想学测试", null);
        makeReady(second, "7_session-b");
        var newer = store.claimDue(1, Duration.ZERO);
        String id = Long.toString(original.id());
        assertThat(store.apply(newer, List.of(new UserMemoryStore.Candidate("goal", "想学测试", second, "想学测试", id)))).isTrue();
        assertThat(store.apply(older, List.of(new UserMemoryStore.Candidate("goal", "想学后端", first, "想学后端", id)))).isTrue();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("想学测试");
    }

    @Test
    void correctionToAnAlreadyKnownFactIsDeduplicated() {
        var original = remember(7, "background", "学过 Java");
        remember(7, "background", "学过 Python");
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-b", request, "之前说错了，我学过 Python", null);
        makeReady(request, "7_session-b");
        assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(new UserMemoryStore.Candidate(
                "background", "学过 Python", request, "学过 Python", Long.toString(original.id()))))).isTrue();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("学过 Python");
    }

    @Test
    void capacityPreservesExistingFactsAndStillAllowsTargetedCorrections() {
        for (int i = 0; i < UserMemoryStore.MAX_MEMORIES; i++) remember(7, "background", "做过练习 " + i);
        var original = store.list(7);
        remember(7, "background", "学过 Python");
        assertThat(store.list(7)).containsExactlyElementsOf(original);
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-b", request, "之前说错了，我做过 Java 项目", null);
        makeReady(request, "7_session-b");
        assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(new UserMemoryStore.Candidate(
                "background", "做过 Java 项目", request, "做过 Java 项目", Long.toString(original.get(0).id()))))).isTrue();
        assertThat(store.list(7)).hasSize(UserMemoryStore.MAX_MEMORIES);
        assertThat(store.list(7).get(0).content()).isEqualTo("做过 Java 项目");
    }

    @Test
    void legacySchemaMigrationPreservesEncryptedSummaryAndAllowsMoreFacts() {
        var original = remember(7, "background", "有 Java 基础，看过微服务");
        String encrypted = jdbc.queryForObject("SELECT content_cipher FROM user_memory_item WHERE id=?", String.class, original.id());
        jdbc.execute("DROP INDEX idx_memory_user_key");
        jdbc.execute("CREATE UNIQUE INDEX uk_memory_user_key ON user_memory_item (user_id,memory_key)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(
                "db/user-memory-multi-fact-migration.sql")).execute(jdbc.getDataSource());

        assertThat(store.list(7)).containsExactly(original);
        assertThat(jdbc.queryForObject("SELECT content_cipher FROM user_memory_item WHERE id=?", String.class, original.id())).isEqualTo(encrypted);
        remember(7, "background", "学过 Python");
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("有 Java 基础，看过微服务", "学过 Python");
    }

    @Test
    void completeTurnIsEncryptedAndAppliedOnlyForItsOwner() {
        String request = UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", request, "我学过一点 Java", null)).isTrue();
        assertThat(jdbc.queryForObject("SELECT payload_cipher FROM user_memory_task", String.class)).doesNotContain("Java");
        assertThat(makeReady(request, "8_session-a")).isFalse();
        assertThat(store.claimDue(1, Duration.ZERO)).isNull();
        assertThat(makeReady(request, "7_session-a")).isTrue();
        var batch = store.claimDue(1, Duration.ZERO);
        assertThat(batch).isNotNull();
        var candidate = new UserMemoryStore.Candidate("background", "学过一点 Java", request, "学过一点 Java");
        assertThat(store.apply(batch, List.of(candidate))).isTrue();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("学过一点 Java");
        assertThat(store.list(8)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT content_cipher FROM user_memory_item", String.class)).doesNotContain("Java");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isZero();
    }

    @Test
    void deletingOrCorrectingInvalidatesAnAlreadyClaimedTask() {
        String first = UUID.randomUUID().toString();
        store.stage(7, "session-a", first, "我学过 Java", null);
        makeReady(first, "7_session-a");
        var firstBatch = store.claimDue(1, Duration.ZERO);
        store.apply(firstBatch, List.of(new UserMemoryStore.Candidate("background", "学过 Java", first, "学过 Java")));
        var item = store.list(7).get(0);
        String stale = UUID.randomUUID().toString();
        store.stage(7, "session-b", stale, "我熟悉 Java", null);
        makeReady(stale, "7_session-b");
        var staleBatch = store.claimDue(1, Duration.ZERO);
        store.delete(7, item.id(), item.version());
        assertThat(store.apply(staleBatch, List.of(new UserMemoryStore.Candidate("background", "熟悉 Java", stale, "熟悉 Java")))).isFalse();
        assertThat(store.list(7)).isEmpty();
        assertThat(store.list(8)).isEmpty();
    }

    @Test
    void duplicateTurnsAndWrongVersionCannotChangeMemory() {
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-a", request, "想学后端", null);
        store.stage(7, "session-a", request, "想学后端", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isEqualTo(1);
        makeReady(request, "7_session-a");
        var batch = store.claimDue(1, Duration.ZERO);
        store.apply(batch, List.of(new UserMemoryStore.Candidate("goal", "想学后端", request, "想学后端")));
        var item = store.list(7).get(0);
        assertThatThrownBy(() -> store.correct(7, item.id(), item.version() + 1, "想学前端")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.delete(8, item.id(), item.version())).isInstanceOf(RuntimeException.class);
        assertThat(store.list(7).get(0).content()).isEqualTo("想学后端");
    }

    @Test
    void unknownSourcesAndSensitiveDataAreNotStored() {
        String request = UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", request, "密码是 abc123", null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isZero();
        store.stage(7, "session-a", request, "想学后端", null);
        makeReady(request, "7_session-a");
        var batch = store.claimDue(1, Duration.ZERO);
        store.apply(batch, List.of(new UserMemoryStore.Candidate("goal", "熟练掌握 Java", request, "不存在的证据")));
        assertThat(store.list(7)).isEmpty();
    }

    @Test
    void correctionDisableAndSessionDeletionInvalidateInFlightWork() {
        String first = UUID.randomUUID().toString();
        store.stage(7, "session-a", first, "我学过 Java", null);
        makeReady(first, "7_session-a");
        store.apply(store.claimDue(1, Duration.ZERO), List.of(new UserMemoryStore.Candidate("background", "学过 Java", first, "学过 Java")));
        var item = store.list(7).get(0);
        String second = UUID.randomUUID().toString();
        store.stage(7, "session-b", second, "我熟悉 Java", null);
        makeReady(second, "7_session-b");
        var stale = store.claimDue(1, Duration.ZERO);
        store.correct(7, item.id(), item.version(), "正在学习 Java");
        assertThat(store.apply(stale, List.of(new UserMemoryStore.Candidate("background", "熟悉 Java", second, "熟悉 Java")))).isFalse();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("正在学习 Java");
        String third = UUID.randomUUID().toString();
        store.stage(7, "session-b", third, "我想学后端", null);
        makeReady(third, "7_session-b");
        var disabledBatch = store.claimDue(1, Duration.ZERO);
        store.setEnabled(7, false);
        assertThat(store.apply(disabledBatch, List.of(new UserMemoryStore.Candidate("goal", "想学后端", third, "想学后端")))).isFalse();
        assertThat(store.context(7)).isEmpty();
        assertThat(store.list(7)).hasSize(1);
        assertThat(store.stage(7, "session-a", UUID.randomUUID().toString(), "我想学前端", null)).isFalse();
        store.setEnabled(7, true);
        String fourth = UUID.randomUUID().toString();
        store.stage(7, "session-b", fourth, "我想学前端", null);
        makeReady(fourth, "7_session-b");
        var deletedSessionBatch = store.claimDue(1, Duration.ZERO);
        store.deletePendingForSession(7, "session-b");
        assertThat(store.apply(deletedSessionBatch, List.of(new UserMemoryStore.Candidate("goal", "想学前端", fourth, "想学前端")))).isFalse();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("正在学习 Java");
    }

    @Test
    void expiredLeaseCannotCommitAfterAnotherWorkerClaimsTask() {
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-a", request, "我想学后端", null);
        makeReady(request, "7_session-a");
        var old = store.claimDue(1, Duration.ZERO);
        jdbc.update("UPDATE user_memory_task SET claim_until=?", java.time.LocalDateTime.now().minusSeconds(1));
        var current = store.claimDue(1, Duration.ZERO);
        store.retry(old);
        var candidate = new UserMemoryStore.Candidate("goal", "想学后端", request, "想学后端");
        assertThat(store.apply(old, List.of(candidate))).isFalse();
        assertThat(store.apply(current, List.of(candidate))).isTrue();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("想学后端");
    }

    @Test
    void deletionFencesARequestThatHasReadAnOlderUserStateButHasNotStagedYet() {
        var oldState = store.state(7);
        store.deleteAll(7);
        assertThat(store.stage(7, "session-a", UUID.randomUUID().toString(), "我想学 Java", null, oldState.epoch())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isZero();
        assertThat(store.stage(7, "session-a", UUID.randomUUID().toString(), "我想学 Java", null, store.state(7).epoch())).isTrue();
    }

    @Test
    void exhaustedTaskIsRetainedEncryptedAndCanBeExplicitlyReplayed() {
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-a", request, "我想学后端", null);
        makeReady(request, "7_session-a");
        for (int attempt = 0; attempt < 3; attempt++) {
            var batch = store.claimDue(1, Duration.ZERO);
            assertThat(batch).isNotNull();
            store.retry(batch);
            jdbc.update("UPDATE user_memory_task SET next_attempt_at=? WHERE state='READY'", java.time.LocalDateTime.now().minusSeconds(1));
        }
        assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload_cipher FROM user_memory_task", String.class)).doesNotContain("后端");
        assertThat(store.claimDue(1, Duration.ZERO)).isNull();
        jdbc.update("UPDATE user_memory_task SET state='READY',attempts=0,next_attempt_at=NULL WHERE request_id=?", request);
        assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(new UserMemoryStore.Candidate("goal", "想学后端", request, "想学后端")))).isTrue();
        assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("想学后端");
    }

    @Test
    void expiredFailedTaskIsPurgedAndUserDeletionAlsoClearsIt() {
        String request = UUID.randomUUID().toString();
        store.stage(7, "session-a", request, "我想学后端", null);
        makeReady(request, "7_session-a");
        var batch = store.claimDue(1, Duration.ZERO);
        jdbc.update("UPDATE user_memory_task SET attempts=3 WHERE request_id=?", request);
        store.retry(new UserMemoryStore.Batch(batch.userId(), batch.sessionId(), batch.epoch(), batch.token(), batch.turns(), batch.snapshot(), 3));
        store.deleteAll(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isZero();
        store.stage(7, "session-a", request, "我想学后端", null);
        makeReady(request, "7_session-a");
        batch = store.claimDue(1, Duration.ZERO);
        store.retry(new UserMemoryStore.Batch(batch.userId(), batch.sessionId(), batch.epoch(), batch.token(), batch.turns(), batch.snapshot(), 3));
        jdbc.update("UPDATE user_memory_task SET next_attempt_at=? WHERE state='FAILED'", java.time.LocalDateTime.now().minusSeconds(1));
        assertThat(store.claimDue(1, Duration.ZERO)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isZero();
    }

    @Test
    void timeoutReleasesTheWorkerAndTheSameTaskCanSucceedOnRetry() {
        var model = mock(org.springframework.ai.chat.model.ChatModel.class);
        var cancelled = new AtomicBoolean();
        String request = UUID.randomUUID().toString();
        String output = "{\"memories\":[{\"key\":\"goal\",\"content\":\"想学后端\",\"requestId\":\"" + request + "\",\"evidence\":\"想学后端\"}]}";
        var response = new org.springframework.ai.chat.model.ChatResponse(List.of(
                new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(output))));
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(reactor.core.publisher.Flux.<org.springframework.ai.chat.model.ChatResponse>never().doOnCancel(() -> cancelled.set(true)))
                .thenReturn(reactor.core.publisher.Flux.just(response));
        store.stage(7, "session-a", request, "我想学后端", null);
        makeReady(request, "7_session-a");
        jdbc.update("UPDATE user_memory_task SET ready_at=?", java.time.LocalDateTime.now().minusMinutes(1));
        try (var worker = new UserMemoryWorker(store, new UserMemoryExtractor(model, Duration.ofMillis(100)),
                io.micrometer.observation.ObservationRegistry.NOOP)) {
            worker.poll();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(cancelled).isTrue();
                assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task", String.class)).isEqualTo("READY");
            });
            jdbc.update("UPDATE user_memory_task SET next_attempt_at=?", java.time.LocalDateTime.now().minusSeconds(1));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                worker.poll();
                assertThat(store.list(7)).extracting(UserMemoryStore.Memory::content).containsExactly("想学后端");
            });
        }
    }

    @Test
    void anUnsealedPersistedTaskIsNotExtractedAndIsEventuallyCleaned() {
        String request = UUID.randomUUID().toString();
        assertThat(store.stage(7, "session-a", request, "我想学 Java", null)).isTrue();
        assertThat(store.markPersisted(request, "7_session-a")).isTrue();
        assertThat(store.claimDue(1, Duration.ZERO)).isNull();
        jdbc.update("UPDATE user_memory_task SET created_at=? WHERE request_id=?", java.time.LocalDateTime.now().minusHours(2), request);
        assertThat(store.claimDue(1, Duration.ZERO)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task", Integer.class)).isZero();
    }
    private UserMemoryStore.Memory remember(long user, String key, String content) {
        String request = UUID.randomUUID().toString();
        assertThat(store.stage(user, "session-a", request, content, null)).isTrue();
        assertThat(makeReady(request, user + "_session-a")).isTrue();
        assertThat(store.apply(store.claimDue(1, Duration.ZERO), List.of(
                new UserMemoryStore.Candidate(key, content, request, content)))).isTrue();
        return store.list(user).stream().filter(item -> item.content().equals(content)).findFirst().orElse(null);
    }

    private boolean makeReady(String requestId, String conversationId) {
        return store.markPersisted(requestId, conversationId) && store.markReady(requestId, conversationId);
    }

}
