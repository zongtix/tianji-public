package com.tianji.learning.service.interaction;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tianji.api.client.aigc.BotReplyClient;
import com.tianji.api.dto.aigc.BotReplyRequest;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.ForbiddenException;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.config.BotReplyProperties;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.vo.ReplySubmissionVO;
import com.tianji.learning.mapper.interaction.BotReplyTaskMapper;
import com.tianji.learning.mapper.interaction.InteractionQuestionMapper;
import com.tianji.learning.mapper.interaction.InteractionReplyMapper;
import com.tianji.learning.service.interaction.impl.InteractionReplyServiceImpl;
import com.tianji.learning.service.points.IPointsMessageService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 Spring/MyBatis/H2 事务；模型和 Redis 为隔离替身，不消耗模型额度。 */
@SpringJUnitConfig(BotReplyFlowTest.Config.class)
class BotReplyFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager tx;
    @Autowired IInteractionReplyService replies;
    @Autowired BotReplyService bot;
    @Autowired BotReplyTaskMapper tasks;
    @Autowired BotReplyClient client;
    @Autowired StringRedisTemplate redis;
    @Autowired BotReplyProperties settings;
    @Autowired IPointsMessageService points;
    @Autowired com.tianji.api.client.user.UserClient users;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        reset(client, redis, points, users);
        settings.setEnabled(true);
        jdbc.execute("DROP TABLE IF EXISTS bot_reply_task");
        Path schema = Path.of("../scripts/qa-bot/schema.sql");
        if (!Files.exists(schema)) schema = Path.of("scripts/qa-bot/schema.sql");
        jdbc.execute(Files.readString(schema));
        jdbc.execute("DROP TABLE IF EXISTS interaction_reply");
        jdbc.execute("DROP TABLE IF EXISTS interaction_question");
        jdbc.execute("CREATE TABLE interaction_question (id BIGINT PRIMARY KEY, title VARCHAR(254), description TEXT, " +
                "course_id BIGINT, chapter_id BIGINT, section_id BIGINT, user_id BIGINT, latest_answer_id BIGINT, " +
                "answer_times INT DEFAULT 0, anonymity BOOLEAN DEFAULT FALSE, hidden BOOLEAN DEFAULT FALSE, " +
                "status INT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE interaction_reply (id BIGINT PRIMARY KEY, question_id BIGINT, answer_id BIGINT DEFAULT 0, " +
                "user_id BIGINT, content TEXT, target_user_id BIGINT, target_reply_id BIGINT, reply_times INT DEFAULT 0, " +
                "liked_times INT DEFAULT 0, liked_version BIGINT DEFAULT 0, hidden BOOLEAN DEFAULT FALSE, anonymity BOOLEAN DEFAULT FALSE, " +
                "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO interaction_question (id, title, description) VALUES (42, '事务为何失效', '讨论 Spring 事务')");
        jdbc.update("INSERT INTO interaction_reply (id, question_id, user_id, content) VALUES (101,42,2,'自调用没有经过代理')");
        when(client.reply(any())).thenReturn("同类内部调用没有经过 Spring 代理，所以事务拦截器不会执行。");
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any());
        UserContext.setUser(1L);
    }

    @AfterEach
    void cleanup() { UserContext.removeUser(); }

    private ReplyDTO comment(boolean mention) {
        ReplyDTO request = new ReplyDTO();
        request.setQuestionId(42L);
        request.setAnswerId(101L);
        request.setTargetReplyId(101L);
        request.setAnonymity(false);
        request.setContent("@天机小助理 为什么自调用会失效？");
        if (mention) request.setMentionedUserIds(List.of(9999L));
        return request;
    }

    @Test
    void plainOrPastedMentionNeverCreatesTaskOrCallsModel() {
        assertThat(replies.saveReply(comment(false)).botStatus()).isEqualTo("NOT_REQUESTED");
        bot.processPending();
        assertThat(tasks.pending()).isEmpty();
        verifyNoInteractions(client, redis);
    }

    @Test
    void committedMentionWritesOneBotCommentWithCorrectIdentityThreadAndNoPoints() {
        ReplySubmissionVO result = replies.saveReply(comment(true));
        assertThat(result.botStatus()).isEqualTo("PENDING");
        bot.processPending();
        bot.processPending();
        var task = tasks.selectById(result.id());
        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        var answer = jdbc.queryForMap("SELECT * FROM interaction_reply WHERE id=?", task.getResponseReplyId());
        assertThat(answer.get("USER_ID")).isEqualTo(9999L);
        assertThat(answer.get("ANSWER_ID")).isEqualTo(101L);
        assertThat(answer.get("TARGET_REPLY_ID")).isEqualTo(result.id());
        assertThat(answer.get("TARGET_USER_ID")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT reply_times FROM interaction_reply WHERE id=101", Integer.class)).isEqualTo(2);
        verify(client, times(1)).reply(any());
        verifyNoInteractions(points);
        assertThat(UserContext.getUser()).isEqualTo(1L);
    }

    @Test
    void rollbackRemovesBothCommentAndTaskAndOtherConnectionCannotSeeUncommittedTask() {
        assertThatThrownBy(() -> new TransactionTemplate(tx).execute(status -> {
            replies.saveReply(comment(true));
            try (Connection c = dataSource.getConnection(); var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM bot_reply_task")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            } catch (Exception e) { throw new IllegalStateException(e); }
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_reply_task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reply", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reply_times FROM interaction_reply WHERE id=101", Integer.class)).isZero();
        bot.processPending();
        verifyNoInteractions(client, redis);
    }

    @Test
    void concurrentInstancesCanClaimOnlyOnce() throws Exception {
        var result = replies.saveReply(comment(true));
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> claim = () -> { barrier.await(); return tasks.claim(result.id()); };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            assertThat(first.get() + second.get()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exceededBudgetDoesNotCallModelOrPublishErrorAsAnswer() {
        doReturn(0L).when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any());
        var result = replies.saveReply(comment(true));
        bot.processPending();
        assertThat(tasks.selectById(result.id()).getStatus()).isEqualTo("RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reply", Integer.class)).isEqualTo(2);
        verifyNoInteractions(client);
    }

    @Test
    void modelFailureDoesNotRetryOrSaveFallbackTextAndCleansWorkerIdentity() {
        when(client.reply(any())).thenAnswer(invocation -> {
            assertThat(UserContext.getUser()).isEqualTo(1L);
            throw new IllegalStateException("model unavailable");
        });
        var result = replies.saveReply(comment(true));
        UserContext.removeUser();
        bot.processPending();
        bot.processPending();
        assertThat(tasks.selectById(result.id()).getStatus()).isEqualTo("FAILED");
        assertThat(UserContext.getUser()).isNull();
        verify(client, times(1)).reply(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reply", Integer.class)).isEqualTo(2);
    }

    @Test
    void redisFailureFailsClosedAndDoesNotSpendTokens() {
        doThrow(new IllegalStateException("redis unavailable")).when(redis)
                .execute(any(RedisScript.class), anyList(), any(), any(), any());
        var result = replies.saveReply(comment(true));
        bot.processPending();
        assertThat(tasks.selectById(result.id()).getStatus()).isEqualTo("FAILED");
        verifyNoInteractions(client);
    }

    @Test
    void hiddenDiscussionDuringGenerationPreventsPublication() {
        var result = replies.saveReply(comment(true));
        when(client.reply(any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE interaction_question SET hidden=TRUE WHERE id=42");
            return "生成完成";
        });
        bot.processPending();
        assertThat(tasks.selectById(result.id()).getStatus()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reply", Integer.class)).isEqualTo(2);
    }

    @Test
    void crossQuestionReferenceRollsBackHumanCommentAndCounter() {
        jdbc.update("INSERT INTO interaction_question (id,title) VALUES (43,'其他问题')");
        ReplyDTO request = comment(true);
        request.setQuestionId(43L);
        assertThatThrownBy(() -> replies.saveReply(request)).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reply", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reply_times FROM interaction_reply WHERE id=101", Integer.class)).isZero();
        verifyNoInteractions(client, redis);
    }

    @Test
    void statusIsOwnedAndDoesNotExposeHiddenContent() {
        var result = replies.saveReply(comment(true));
        UserContext.setUser(2L);
        assertThatThrownBy(() -> bot.status(result.id())).isInstanceOf(ForbiddenException.class);
        UserContext.setUser(1L);
        assertThat(bot.status(result.id()).status()).isEqualTo("PENDING");
        jdbc.update("UPDATE interaction_reply SET hidden=TRUE WHERE id=?", result.id());
        assertThatThrownBy(() -> bot.status(result.id())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requestContextIsBoundedAndContainsTargetAnswerAndCurrentQuestion() {
        jdbc.update("UPDATE interaction_reply SET content=? WHERE id=101", "List<String> 与代理机制" + "长".repeat(4000));
        var request = comment(true);
        request.setContent("@天机小助理 " + "请解释".repeat(1000));
        replies.saveReply(request);
        bot.processPending();
        var captured = org.mockito.ArgumentCaptor.forClass(BotReplyRequest.class);
        verify(client).reply(captured.capture());
        assertThat(captured.getValue().getTitle()).isEqualTo("事务为何失效");
        assertThat(captured.getValue().getAnswer()).hasSize(3000).startsWith("List<String> 与代理机制");
        assertThat(captured.getValue().getComment()).hasSize(2000);
    }

    @Test
    void bareMentionIsRejectedAndBotCannotTriggerItself() {
        var request = comment(true);
        request.setContent("@天机小助理 ");
        assertThatThrownBy(() -> replies.saveReply(request)).isInstanceOf(BadRequestException.class);
        UserContext.setUser(9999L);
        assertThat(replies.saveReply(comment(true)).botStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(tasks.pending()).isEmpty();
    }

    @Test
    void botIdentityRequiresCompletedTaskEvenWhenBotIsDisabled() {
        jdbc.update("INSERT INTO interaction_reply (id, question_id, user_id, content) VALUES (102,42,9999,'普通用户')");
        jdbc.update("INSERT INTO interaction_reply (id, question_id, user_id, content) VALUES (103,42,9999,'机器人回复')");
        jdbc.update("INSERT INTO bot_reply_task (source_reply_id,user_id,status,response_reply_id) VALUES (101,1,'COMPLETED',103)");
        var user = new com.tianji.api.dto.user.UserDTO(); user.setId(9999L); user.setName("真实用户");
        when(users.queryUserByIds(any())).thenReturn(List.of(user));
        settings.setEnabled(false);
        var ordinary = replies.queryReplyById(102L);
        assertThat(ordinary.getBot()).isFalse();
        assertThat(ordinary.getUserName()).isEqualTo("真实用户");
        var generated = replies.queryReplyById(103L);
        assertThat(generated.getBot()).isTrue();
        assertThat(generated.getUserName()).isEqualTo(settings.getName());
        jdbc.execute("DROP TABLE bot_reply_task");
        assertThat(replies.queryReplyById(102L).getBot()).isFalse();
    }

    @Test
    void disabledBotKeepsCommentAndDoesNotNeedTaskTable() {
        settings.setEnabled(false);
        jdbc.execute("DROP TABLE bot_reply_task");
        assertThat(replies.saveReply(comment(true)).botStatus()).isEqualTo("UNAVAILABLE");
        bot.processPending();
        verifyNoInteractions(client, redis);
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.tianji.learning.mapper.interaction")
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:qa-bot;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate jdbc(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager tx(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean SqlSessionFactory sessionFactory(DataSource dataSource) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDefaultEnumTypeHandler(MybatisEnumTypeHandler.class);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean BotReplyProperties settings() { return new BotReplyProperties(); }
        @Bean BotReplyClient client() { return mock(BotReplyClient.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean IPointsMessageService points() { return mock(IPointsMessageService.class); }
        @Bean com.tianji.api.client.user.UserClient users() { return mock(com.tianji.api.client.user.UserClient.class); }
        @Bean BotReplyService bot(BotReplyProperties p, BotReplyTaskMapper tasks, InteractionReplyMapper replies,
                                 InteractionQuestionMapper questions, BotReplyClient client,
                                 StringRedisTemplate redis, PlatformTransactionManager tx) {
            return new BotReplyService(p, tasks, replies, questions, client, redis, tx);
        }
        @Bean IInteractionReplyService replies(InteractionQuestionMapper questions, IPointsMessageService points, BotReplyService bot,
                                                com.tianji.api.client.user.UserClient users) {
            return new InteractionReplyServiceImpl(questions, users, null, points, bot);
        }
    }
}
