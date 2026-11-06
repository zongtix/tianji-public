package com.tianji.aigc.memory;

import com.tianji.aigc.mapper.ChatMemoryMessageMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_MYSQL_URL", matches = ".+")
class MysqlChatMemoryIntegrationTest extends ChatMemoryRepositoryContract {
    private static ChatMemoryMessageMapper mapper;
    private static DataSourceTransactionManager transactions;

    @BeforeAll
    static void connect() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("TJ_AIGC_TEST_MYSQL_URL"),
                System.getenv("TJ_AIGC_TEST_MYSQL_USER"), System.getenv("TJ_AIGC_TEST_MYSQL_PASSWORD"));
        Configuration configuration = new Configuration();
        configuration.addMapper(ChatMemoryMessageMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ChatMemoryMessageMapper.class);
        transactions = new DataSourceTransactionManager(source);
    }

    @Override
    protected ChatMemoryRepository openRepository() {
        return new MysqlChatMemoryRepository(mapper, transactions);
    }

    @Test
    void failureAfterActualInsertRollsBackEntireMysqlReplacement() {
        repository.saveAll(conversation, List.of(new UserMessage("原消息")));
        ChatMemoryMessageMapper failing = mock(ChatMemoryMessageMapper.class, delegatesTo(mapper));
        doAnswer(invocation -> {
            mapper.insertMessages(invocation.getArgument(0), invocation.getArgument(1));
            throw new IllegalStateException("forced failure after insert");
        }).when(failing).insertMessages(anyString(), anyList());
        ChatMemoryRepository failingRepository = new MysqlChatMemoryRepository(failing, transactions);
        assertThatThrownBy(() -> failingRepository.saveAll(conversation, List.of(new UserMessage("新消息"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByConversationId(conversation)).extracting(Message::getText).containsExactly("原消息");
    }
}
