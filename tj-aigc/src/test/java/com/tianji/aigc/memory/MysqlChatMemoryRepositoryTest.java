package com.tianji.aigc.memory;

import com.tianji.aigc.mapper.ChatMemoryMessageMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MysqlChatMemoryRepositoryTest extends ChatMemoryRepositoryContract {
    private static ChatMemoryMessageMapper mapper;
    private static DataSourceTransactionManager transactionManager;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:chat_memory;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE chat_message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    conversation_id VARCHAR(128) NOT NULL,
                    message_index INT NOT NULL,
                    message_json LONGTEXT NOT NULL,
                    UNIQUE (conversation_id, message_index)
                )
                """);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(ChatMemoryMessageMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessions = factory.getObject();
        mapper = new SqlSessionTemplate(sessions).getMapper(ChatMemoryMessageMapper.class);
        transactionManager = new DataSourceTransactionManager(source);
    }

    @Override
    protected ChatMemoryRepository openRepository() {
        return new MysqlChatMemoryRepository(mapper, transactionManager);
    }

    @Test
    void failedInsertRollsBackDeletionOfExistingMessages() {
        repository.saveAll(conversation, List.of(new UserMessage("保留原来的消息")));
        jdbc.execute("ALTER TABLE chat_message ADD CONSTRAINT reject_failure CHECK (message_json NOT LIKE '%force-failure%')");
        try {
            assertThatThrownBy(() -> repository.saveAll(conversation, List.of(new UserMessage("force-failure"))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(repository.findByConversationId(conversation)).extracting(Message::getText)
                    .containsExactly("保留原来的消息");
        } finally {
            jdbc.execute("ALTER TABLE chat_message DROP CONSTRAINT reject_failure");
        }
    }
}
