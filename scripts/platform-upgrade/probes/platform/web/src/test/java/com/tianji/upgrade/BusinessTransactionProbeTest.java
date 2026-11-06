package com.tianji.upgrade;

import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.api.GlobalTransaction;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real Feign -> exam-service -> MySQL branch, coordinated by the task Seata server. */
@EnabledIfEnvironmentVariable(named = "TJ_UPGRADE_TEST_EXAM", matches = "true")
@SpringBootTest(classes = ProbeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"spring.cloud.nacos.config.namespace=tianji-platform-upgrade-test-boot3",
                "spring.cloud.nacos.discovery.namespace=tianji-platform-upgrade-test-boot3"})
@Import(BusinessTransactionProbeTest.FeignConfiguration.class)
@DirtiesContext
class BusinessTransactionProbeTest {
    private static final long FIXTURE = 900099004L;
    @Autowired JdbcTemplate local;
    @Autowired TransactionTemplate transactions;
    @Autowired Environment environment;
    @Autowired ExamClient exam;
    private JdbcTemplate remote;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableFeignClients(clients = ExamClient.class)
    static class FeignConfiguration { }

    @FeignClient(name = "exam-service", contextId = "exam-business-probe")
    public interface ExamClient {
        @PostMapping("/question-biz/list")
        void save(@RequestBody List<QuestionLink> links, @RequestHeader("user-info") String user);
    }

    public record QuestionLink(Long bizId, Long questionId) { }

    @BeforeEach void prepare() {
        assertTrue(environment.getRequiredProperty("spring.datasource.url").contains("/tj_upgrade_learning?"));
        String password = System.getenv("TJ_UPGRADE_TEST_PASSWORD");
        assertNotNull(password);
        assertTrue(password.matches("[0-9a-f]{48}"));
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/tj_upgrade_exam?serverTimezone=Asia/Shanghai", "tj_upgrade", password);
        remote = new JdbcTemplate(source);
        assertEquals("tj_upgrade_exam", remote.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(0, links());
        local.execute("CREATE TABLE IF NOT EXISTS platform_upgrade_probe (id BIGINT PRIMARY KEY, amount INT NOT NULL)");
        local.update("DELETE FROM platform_upgrade_probe WHERE id=?", FIXTURE);
        local.update("INSERT INTO platform_upgrade_probe(id,amount) VALUES (?,10)", FIXTURE);
    }

    @AfterEach void cleanup() {
        assertNull(RootContext.getXID(), "Global transaction context must be cleared");
        if (remote != null) remote.update("DELETE FROM question_biz WHERE biz_id=?", FIXTURE);
        local.update("DELETE FROM platform_upgrade_probe WHERE id=?", FIXTURE);
    }

    @Test void rollbackUndoesBothCommittedBranchesAndPropagatesXid() throws Exception {
        GlobalTransaction global = GlobalTransactionContext.getCurrentOrCreate();
        global.begin(30000, "isolated-exam-rollback");
        try {
            writeBothBranches();
            assertEquals(20, amount());
            assertEquals(2, links());
            assertEquals(1, remote.queryForObject("SELECT COUNT(*) FROM undo_log WHERE xid=?", Integer.class, global.getXid()),
                    "Exam branch must receive the same XID over Feign");
        } finally {
            global.rollback();
        }
        for (int i = 0; i < 40 && (amount() != 10 || links() != 0); i++) Thread.sleep(250);
        assertEquals(10, amount());
        assertEquals(0, links());
    }

    @Test void commitKeepsBothBranches() throws Exception {
        GlobalTransaction global = GlobalTransactionContext.getCurrentOrCreate();
        global.begin(30000, "isolated-exam-commit");
        try {
            writeBothBranches();
            global.commit();
        } catch (Exception | AssertionError failure) {
            global.rollback();
            throw failure;
        }
        assertEquals(20, amount());
        assertEquals(2, links());
    }

    private void writeBothBranches() {
        transactions.execute(status -> {
            local.update("UPDATE platform_upgrade_probe SET amount=20 WHERE id=?", FIXTURE);
            return null;
        });
        exam.save(List.of(new QuestionLink(FIXTURE, 900099010L), new QuestionLink(FIXTURE, 900099011L)), "900000002");
    }

    private int amount() {
        return local.queryForObject("SELECT amount FROM platform_upgrade_probe WHERE id=?", Integer.class, FIXTURE);
    }

    private int links() {
        return remote.queryForObject("SELECT COUNT(*) FROM question_biz WHERE biz_id=?", Integer.class, FIXTURE);
    }
}
