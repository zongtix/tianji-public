package com.tianji.aigc.memory;

import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.service.ChatGenerationStore;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.aigc.service.impl.ChatSessionServiceImpl;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 两种数据库执行同一组行为检查；只删除本用例随机创建的对话。 */
abstract class ChatMemoryRepositoryContract {
    protected ChatMemoryRepository repository;
    protected String conversation;
    private String other;

    protected abstract ChatMemoryRepository openRepository();

    @BeforeEach
    void prepareConversation() {
        repository = openRepository();
        conversation = "42_memory-test-" + UUID.randomUUID();
        other = "43_memory-test-" + UUID.randomUUID();
    }

    @AfterEach
    void deleteTestMessages() {
        repository.deleteByConversationId(conversation);
        repository.deleteByConversationId(other);
    }

    @Test
    void courseCardsSurviveFreshMemoryAndRepositoryThenAppearInHistory() {
        var card = Map.of("courseId", "9007199254740993", "name", "Java 入门", "priceInCents", 1999);
        repository.saveAll(conversation, List.of(new UserMessage("查询课程"),
                AssistantMessage.builder().content("课程信息")
                        .properties(Map.of(MessageUtil.COURSE_CARDS, List.of(card))).build()));
        var freshMemory = MessageWindowChatMemory.builder().chatMemoryRepository(openRepository()).build();
        UserContext.setUser(42L);
        try {
            var sessions = spy(new ChatSessionServiceImpl(mock(SessionProperties.class), freshMemory, new com.tianji.aigc.knowledge.KnowledgeBaseAccess(), mock(com.tianji.aigc.service.ChatGenerationStore.class), mock(org.springframework.transaction.PlatformTransactionManager.class)));
            doReturn(com.tianji.aigc.entity.ChatSession.builder().userId(42L).build())
                    .when(sessions).requireOwnedSession(conversation.substring(3));
            var history = sessions.queryBySessionId(conversation.substring(3));
            var cards = new com.fasterxml.jackson.databind.ObjectMapper()
                    .valueToTree(history.get(1).getParams().get(MessageUtil.COURSE_CARDS));
            assertThat(history.get(1).getContent()).isEqualTo("课程信息");
            assertThat(cards.get(0).get("courseId").asText()).isEqualTo("9007199254740993");
            assertThat(cards.get(0).get("priceInCents").asInt()).isEqualTo(1999);
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void unknownConversationIsEmpty() {
        assertThat(repository.findByConversationId(conversation)).isEmpty();
    }

    @Test
    void replacementPreservesOrderAndStoppedMetadataAcrossRepositoryInstances() {
        repository.saveAll(conversation, List.of(new UserMessage("旧问题")));
        List<Message> messages = List.of(new UserMessage("记住枫叶🍁"),
                AssistantMessage.builder().content("部分回答\n第二行")
                        .properties(Map.of(MessageUtil.GENERATION_STATUS, MessageUtil.STOPPED)).build());
        repository.saveAll(conversation, messages);
        repository.saveAll(conversation, messages);
        List<Message> restored = openRepository().findByConversationId(conversation);
        assertThat(restored).extracting(Message::getText).containsExactly("记住枫叶🍁", "部分回答\n第二行");
        assertThat(restored).extracting(Message::getMessageType).containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(MessageUtil.isStopped(restored.get(1))).isTrue();
    }

    @Test
    void conversationsStaySeparateAndEmptySaveRemovesConversationId() {
        repository.saveAll(conversation, List.of(new UserMessage("first")));
        repository.saveAll(other, List.of(new UserMessage("other")));
        assertThat(repository.findConversationIds()).contains(conversation, other);
        repository.saveAll(conversation, List.of());
        assertThat(repository.findByConversationId(conversation)).isEmpty();
        assertThat(repository.findConversationIds()).doesNotContain(conversation).contains(other);
        assertThat(repository.findByConversationId(other)).extracting(Message::getText).containsExactly("other");
    }

    @Test
    void deleteIsRepeatableAndLeavesOtherConversationIntact() {
        repository.saveAll(conversation, List.of(new UserMessage("remove")));
        repository.saveAll(other, List.of(new UserMessage("retain")));
        repository.deleteByConversationId(conversation);
        repository.deleteByConversationId(conversation);
        assertThat(repository.findByConversationId(conversation)).isEmpty();
        assertThat(repository.findByConversationId(other)).extracting(Message::getText).containsExactly("retain");
    }

    @Test
    void windowEvictionPersistsOnlyCurrentWindow() {
        ChatMemory memory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(3).build();
        memory.add(conversation, List.of(new UserMessage("one"), new AssistantMessage("two")));
        memory.add(conversation, List.of(new UserMessage("three"), new AssistantMessage("four")));
        assertThat(openRepository().findByConversationId(conversation)).hasSizeLessThanOrEqualTo(3)
                .extracting(Message::getText).doesNotContain("one").endsWith("three", "four");
    }

    @Test
    void streamingAdvisorUsesPersistedContextOnNextRequest() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("已记住"))))));
        ChatClient firstClient = client(model, repository);
        send(firstClient, "记住 maple-42");
        // 新建 Memory 和 Advisor，确保上下文来自持久化存储。
        send(client(model, openRepository()), "刚才让你记住什么？");
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).stream(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions())
                .filteredOn(message -> message.getMessageType() != MessageType.SYSTEM)
                .extracting(Message::getText).containsExactly("记住 maple-42", "已记住", "刚才让你记住什么？");
        assertThat(repository.findByConversationId(conversation)).hasSize(4);
    }

    @Test
    void stopPersistsPartialAnswerBeforeTerminalEventAndHistoryExposesStatus() {
        UserContext.setUser(42L);
        Disposable subscription = null;
        try {
            String session = conversation.substring("42_".length());
            AtomicBoolean stopped = new AtomicBoolean();
            ChatGenerationStore store = mock(ChatGenerationStore.class);
            ChatGenerationStore.Generation generation = mock(ChatGenerationStore.Generation.class);
            when(generation.isValid()).thenReturn(true);
            when(store.acquire(session)).thenReturn(generation);
            when(store.renew(generation)).thenAnswer(invocation -> !stopped.get());
            when(store.isCurrent(generation)).thenReturn(true);
            doAnswer(invocation -> { stopped.set(true); return null; }).when(store).stop(session);
            Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(source.asFlux());
            ChatMemory memory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).build();
            ChatClient client = ChatClient.builder(model)
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
            SystemPromptConfig prompt = mock(SystemPromptConfig.class);
            when(prompt.getChatSystemMessage()).thenReturn(new AtomicReference<>("test system"));
            ChatServiceImpl service = com.tianji.aigc.ChatTestSupport.ordinaryChat(client, prompt, store, memory);
            List<ChatEventVO> events = new CopyOnWriteArrayList<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            AtomicBoolean complete = new AtomicBoolean();
            subscription = service.chat("测试停止", session).subscribe(events::add, error::set, () -> complete.set(true));
            await().atMost(Duration.ofSeconds(10)).until(() -> source.currentSubscriberCount() == 1);
            source.emitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("已收到的部分回答")))),
                    Sinks.EmitFailureHandler.FAIL_FAST);
            await().atMost(Duration.ofSeconds(10)).until(() -> !events.isEmpty());
            service.stop(session);
            await().atMost(Duration.ofSeconds(10)).until(complete::get);
            assertThat(error.get()).isNull();
            assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1001, 1002);
            List<Message> saved = openRepository().findByConversationId(conversation);
            assertThat(saved).extracting(Message::getText).containsExactly("测试停止", "已收到的部分回答");
            assertThat(MessageUtil.isStopped(saved.get(1))).isTrue();
            var sessions = spy(new ChatSessionServiceImpl(mock(SessionProperties.class), memory, new com.tianji.aigc.knowledge.KnowledgeBaseAccess(), mock(com.tianji.aigc.service.ChatGenerationStore.class), mock(org.springframework.transaction.PlatformTransactionManager.class)));
            doReturn(com.tianji.aigc.entity.ChatSession.builder().userId(42L).build())
                    .when(sessions).requireOwnedSession(session);
            var history = sessions.queryBySessionId(session);
            assertThat(history).hasSize(2);
            assertThat(history.get(1).getParams()).containsEntry(MessageUtil.GENERATION_STATUS, MessageUtil.STOPPED);
        } finally {
            if (subscription != null) subscription.dispose();
            UserContext.removeUser();
        }
    }

    private ChatClient client(ChatModel model, ChatMemoryRepository storage) {
        ChatMemory memory = MessageWindowChatMemory.builder().chatMemoryRepository(storage).build();
        return ChatClient.builder(model).defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
    }

    private void send(ChatClient client, String question) {
        assertThat(client.prompt().user(question)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversation))
                .stream().content().collectList().block(Duration.ofSeconds(10))).containsExactly("已记住");
    }
}
