package com.tianji.aigc.longterm;

import com.tianji.aigc.agent.AgentDispatcher;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.mapper.ChatMemoryMessageMapper;
import com.tianji.aigc.memory.MysqlChatMemoryRepository;
import com.tianji.aigc.service.ChatGenerationStore;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.service.ChatTitleGenerator;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只调用释放交接边界；所有协调/模型均为 mock，不连接真实 Redis 或数据库。 */
@ExtendWith(OutputCaptureExtension.class)
class UserMemoryReleaseTest {
    private static final String REQUEST = "12345678-1234-1234-1234-123456789abc";
    private static final String CONVERSATION = "7_session-a";
    private UserMemoryService memory;
    private ChatGenerationStore generations;
    private ChatGenerationStore.Generation generation;
    private ChatServiceImpl service;

    @BeforeEach
    void setup() {
        memory = mock(UserMemoryService.class);
        generations = mock(ChatGenerationStore.class);
        generation = mock(ChatGenerationStore.Generation.class);
        when(generation.requestId()).thenReturn(REQUEST);
        service = new ChatServiceImpl(mock(AgentDispatcher.class), generations, mock(ChatMemory.class),
                mock(ChatTitleGenerator.class), mock(ChatSessionService.class), new KnowledgeBaseAccess(),
                new DefaultListableBeanFactory().getBeanProvider(KnowledgeRetrievalAdvisor.class), ObservationRegistry.NOOP);
        ReflectionTestUtils.setField(service, "userMemory", memory);
        when(memory.isPersisted(REQUEST, CONVERSATION)).thenReturn(true);
    }

    @Test
    void readyRejectionIsObservableAndStillAbandonsOnlyUnsealedWork(CapturedOutput output) {
        when(generations.complete(generation)).thenReturn(true);
        when(memory.markReady(REQUEST, CONVERSATION)).thenReturn(false);
        release(true);
        var order = inOrder(generations, memory);
        order.verify(generations).complete(generation);
        order.verify(memory).markReady(REQUEST, CONVERSATION);
        order.verify(memory).abandon(REQUEST);
        order.verify(generations).release(generation);
        assertThat(output.getAll()).contains("交接未接受 READY", "requestRef=").doesNotContain(REQUEST);
    }

    @Test
    void completionRejectionNeverRevivesAStoppedOrExpiredTask(CapturedOutput output) {
        when(generations.complete(generation)).thenReturn(false);
        release(true);
        verify(memory, never()).markReady(anyString(), anyString());
        verify(memory).abandon(REQUEST);
        verify(generations).release(generation);
        assertThat(output.getAll()).contains("reason=completion_rejected").doesNotContain(REQUEST);
    }

    @Test
    void stoppedOrCancelledReleaseDoesNotAttemptCompletionOrExtraction() {
        release(false);
        verify(memory, never()).isPersisted(anyString(), anyString());
        verify(generations, never()).complete(any());
        verify(memory, never()).markReady(anyString(), anyString());
        verify(memory).abandon(REQUEST);
        verify(generations).release(generation);
    }

    @Test
    void taskThatWasNotStagedIsNotPromoted() {
        when(memory.isPersisted(REQUEST, CONVERSATION)).thenReturn(false);
        release(true);
        verify(generations, never()).complete(any());
        verify(memory, never()).markReady(anyString(), anyString());
        verify(memory).abandon(REQUEST);
    }

    @Test
    void successfulHandoffStillPerformsSafeAbandonAndReleasesLease() {
        when(generations.complete(generation)).thenReturn(true);
        when(memory.markReady(REQUEST, CONVERSATION)).thenReturn(true);
        release(true);
        verify(memory).markReady(REQUEST, CONVERSATION);
        verify(memory).abandon(REQUEST);
        verify(generations).release(generation);
    }

    @Test
    void unexpectedHandoffExceptionDoesNotLeakMessageOrSkipCleanup(CapturedOutput output) {
        when(memory.isPersisted(REQUEST, CONVERSATION)).thenThrow(new IllegalStateException("PRIVATE_SENTINEL"));
        release(true);
        verify(memory).abandon(REQUEST);
        verify(generations).release(generation);
        assertThat(output.getAll()).contains("确认长期记忆任务失败", "IllegalStateException").doesNotContain("PRIVATE_SENTINEL");
    }

    @Test
    void persistedRejectionIsObservableWithoutLosingChat(CapturedOutput output) {
        var mapper = mock(ChatMemoryMessageMapper.class);
        var store = mock(UserMemoryStore.class);
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
        var repository = new MysqlChatMemoryRepository(mapper, new DataSourceTransactionManager(source), store);
        var answer = AssistantMessage.builder().content("PRIVATE_ANSWER_SENTINEL")
                .properties(Map.of(UserMemoryCompletionAdvisor.REQUEST_ID, REQUEST)).build();
        repository.saveAll(CONVERSATION, List.of(answer));
        verify(mapper).deleteMessages(CONVERSATION);
        verify(mapper).insertMessages(eq(CONVERSATION), argThat(rows -> rows.size() == 1 && rows.get(0).contains("PRIVATE_ANSWER_SENTINEL")));
        verify(store).markPersisted(REQUEST, CONVERSATION);
        assertThat(output.getAll()).contains("交接未接受 PERSISTED", "requestRef=")
                .doesNotContain(REQUEST, "PRIVATE_ANSWER_SENTINEL");
    }

    private void release(boolean completed) {
        Mono<Void> result = ReflectionTestUtils.invokeMethod(service, "release", generation, CONVERSATION, completed);
        assertThat(result).isNotNull();
        result.block(Duration.ofSeconds(5));
    }
}
