package com.tianji.aigc.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 内存 H2 记录清理任务，Mongo 清理动作 mock；不打开 Mongo 连接。 */
@ExtendWith(OutputCaptureExtension.class)
class MongoMemoryCleanupTest {
    private JdbcTemplate jdbc;
    private ChatMemory memory;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE chat_memory_cleanup (store_id CHAR(64), conversation_id VARCHAR(128), last_attempt_at TIMESTAMP, PRIMARY KEY(store_id, conversation_id))");
        memory = mock(ChatMemory.class);
    }

    @Test
    void repeatedEnqueueIsIdempotentAndPreservesRetryTimestamp() {
        var cleanup = cleanup("mongodb://localhost/fixture");
        cleanup.enqueue("7_session");
        LocalDateTime attempted = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbc.update("UPDATE chat_memory_cleanup SET last_attempt_at=?", attempted);
        cleanup.enqueue("7_session");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_attempt_at FROM chat_memory_cleanup", LocalDateTime.class)).isEqualTo(attempted);
        cleanup.retryPending();
        cleanup.retryPending();
        assertThat(count()).isZero();
        verify(memory, times(1)).clear("7_session");
    }

    @Test
    void compensationFailureDoesNotBlockOtherItemsAndRetainsFailedTask(CapturedOutput output) {
        JdbcTemplate failingJdbc = spy(jdbc);
        var cleanup = new MongoMemoryCleanup(failingJdbc, memory, "mongodb://localhost/fixture");
        cleanup.enqueue("a_failure");
        cleanup.enqueue("b_healthy");
        doThrow(new IllegalStateException("PRIVATE_MONGO_ERROR")).when(memory).clear("a_failure");
        doThrow(new DataAccessResourceFailureException("PRIVATE_SQL_ERROR")).when(failingJdbc)
                .update(startsWith("UPDATE chat_memory_cleanup SET last_attempt_at"), any(), eq("a_failure"));
        cleanup.retryPending();
        verify(memory).clear("b_healthy");
        assertThat(jdbc.queryForList("SELECT conversation_id FROM chat_memory_cleanup", String.class)).containsExactly("a_failure");
        assertThat(output.getAll()).contains("记录 MongoDB 清理重试时间失败").doesNotContain("PRIVATE_MONGO_ERROR", "PRIVATE_SQL_ERROR");
    }

    @Test
    void sqlDeleteFailureRetainsTaskForAnIdempotentSecondRemoteDelete() {
        JdbcTemplate failingJdbc = spy(jdbc);
        var cleanup = new MongoMemoryCleanup(failingJdbc, memory, "mongodb://localhost/fixture");
        cleanup.enqueue("a_failure");
        cleanup.enqueue("b_healthy");
        doThrow(new DataAccessResourceFailureException("synthetic"))
                .doCallRealMethod().when(failingJdbc).update(startsWith("DELETE FROM chat_memory_cleanup"), any(), eq("a_failure"));
        cleanup.retryPending();
        verify(memory).clear("b_healthy");
        assertThat(count()).isEqualTo(1);
        cleanup.retryPending();
        verify(memory, times(2)).clear("a_failure");
        assertThat(count()).isZero();
    }

    @Test
    void credentialsAndHostOrderDoNotChangeStoreIdentity() {
        cleanup("mongodb://user:old-secret@host1,host2/fixture?replicaSet=rs").enqueue("7_session");
        cleanup("mongodb://another:new-secret@host2,host1/fixture?replicaSet=rs").retryPending();
        verify(memory).clear("7_session");
        assertThat(count()).isZero();
    }

    @Test
    void differentHostDatabaseOrReplicaSetNeverReceivesOldStoreTasksAndRaisesAlert(CapturedOutput output) {
        cleanup("mongodb://user:old-secret@host1/fixture?replicaSet=rs").enqueue("PRIVATE_SESSION");
        for (String uri : new String[]{"mongodb://host2/fixture?replicaSet=rs", "mongodb://host1/other?replicaSet=rs", "mongodb://host1/fixture?replicaSet=other"}) {
            cleanup(uri).retryPending();
        }
        verifyNoInteractions(memory);
        assertThat(count()).isEqualTo(1);
        assertThat(output.getAll()).contains("须连接原库人工核对处理", "pending=1")
                .doesNotContain("old-secret", "PRIVATE_SESSION", "mongodb://");
        // 只有重新连接原身份，才能安全完成其清理队列。
        cleanup("mongodb://host1/fixture?replicaSet=rs").retryPending();
        verify(memory).clear("PRIVATE_SESSION");
        assertThat(count()).isZero();
    }

    private MongoMemoryCleanup cleanup(String uri) { return new MongoMemoryCleanup(jdbc, memory, uri); }
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_cleanup", Integer.class); }
}
