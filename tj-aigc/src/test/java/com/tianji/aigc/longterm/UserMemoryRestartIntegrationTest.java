package com.tianji.aigc.longterm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Run seed and verify in two separate Java processes against the disposable local instance. */
class UserMemoryRestartIntegrationTest {
    private static final long USER = 990001;
    private static final String SESSION = "restart-session";
    private static final String REQUEST = "123e4567-e89b-42d3-a456-426614174000";
    private JdbcTemplate jdbc;
    private UserMemoryStore store;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13316/memory_acceptance?characterEncoding=utf8&serverTimezone=Asia/Shanghai", "root", "");
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13316);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("memory_acceptance");
        assertThat(jdbc.queryForObject("SELECT @@datadir", String.class).replace('\\', '/'))
                .contains("/work/memory-acceptance/mysql-data/");
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        store = new UserMemoryStore(jdbc, new DataSourceTransactionManager(source), new UserMemoryCipher(key));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TJ_MEMORY_RESTART_PHASE", matches = "seed")
    void writeReadyTaskBeforeProcessExit() {
        store.deleteAll(USER);
        store.setEnabled(USER, true);
        assertThat(store.stage(USER, SESSION, REQUEST, "我想学 Java", null)).isTrue();
        assertThat(store.markPersisted(REQUEST, USER + "_" + SESSION)).isTrue();
        assertThat(store.markReady(REQUEST, USER + "_" + SESSION)).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task WHERE request_id=?", String.class, REQUEST)).isEqualTo("READY");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TJ_MEMORY_RESTART_PHASE", matches = "verify")
    void anotherProcessRecoversAndAppliesTheReadyTask() {
        assertThat(jdbc.queryForObject("SELECT state FROM user_memory_task WHERE request_id=?", String.class, REQUEST)).isEqualTo("READY");
        var batch = store.claimDue(1, Duration.ZERO);
        assertThat(batch).isNotNull();
        assertThat(batch.userId()).isEqualTo(USER);
        assertThat(store.apply(batch, List.of(new UserMemoryStore.Candidate("goal", "想学 Java", REQUEST, "想学 Java")))).isTrue();
        assertThat(store.list(USER)).extracting(UserMemoryStore.Memory::content).containsExactly("想学 Java");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_task WHERE request_id=?", Integer.class, REQUEST)).isZero();
    }
}
