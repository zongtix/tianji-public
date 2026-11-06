package com.tianji.aigc.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisChatMemoryRepositoryTest {
    private RedisChatMemoryRepository repository;
    private StringRedisTemplate redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Map<String, List<String>> stored = new HashMap<>();
        redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(lists.range(anyString(), eq(0L), eq(-1L))).thenAnswer(invocation -> {
            List<String> messages = stored.get(invocation.getArgument(0));
            return messages == null ? List.of() : new ArrayList<>(messages);
        });
        // 模拟一次服务端窗口替换；脚本的真实执行由父线程另行选择隔离 Redis 验证。
        doAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            Object[] arguments = (Object[]) invocation.getRawArguments()[2];
            List<String> messages = java.util.Arrays.stream(arguments).map(String.class::cast).toList();
            if (messages.isEmpty()) stored.remove(keys.get(0));
            else stored.put(keys.get(0), messages);
            return 1L;
        }).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        when(redis.delete(anyString())).thenAnswer(invocation -> stored.remove(invocation.getArgument(0)) != null);
        repository = new RedisChatMemoryRepository();
        ReflectionTestUtils.setField(repository, "stringRedisTemplate", redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacementUsesOneServerOperationAndEmptyWindowDeletesTheConversation() {
        repository.saveAll("42_replace", List.of(new org.springframework.ai.chat.messages.UserMessage("old")));
        clearInvocations(redis);
        repository.saveAll("42_replace", List.of(new org.springframework.ai.chat.messages.UserMessage("one"), new AssistantMessage("two")));
        verify(redis, times(1)).execute(any(RedisScript.class), eq(List.of("CHAT:42_replace")), any(Object[].class));
        verify(redis, never()).delete(anyString());
        verify(redis, never()).boundListOps(anyString());
        assertThat(repository.findByConversationId("42_replace")).extracting(Message::getText).containsExactly("one", "two");
        repository.saveAll("42_replace", List.of());
        assertThat(repository.findByConversationId("42_replace")).isEmpty();
    }

    @Test
    void serializationFailureOccursBeforeAnyRedisMutation() {
        repository.saveAll("42_replace", List.of(new org.springframework.ai.chat.messages.UserMessage("original")));
        clearInvocations(redis);
        assertThatThrownBy(() -> repository.saveAll("42_replace", java.util.Arrays.asList(new AssistantMessage("new"), null)))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(redis);
        assertThat(repository.findByConversationId("42_replace")).extracting(Message::getText).containsExactly("original");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scanDeduplicatesClosesCursorAndRemovesOnlyTheLeadingPrefix() {
        Cursor<String> cursor = mock(Cursor.class);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        var keys = List.of("CHAT:first", "CHAT:xCHAT:y", "CHAT:first").iterator();
        when(cursor.hasNext()).thenAnswer(call -> keys.hasNext());
        when(cursor.next()).thenAnswer(call -> keys.next());
        assertThat(repository.findConversationIds()).containsExactly("first", "xCHAT:y");
        verify(cursor).close();
        verify(redis, never()).keys(anyString());
        var options = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redis).scan(options.capture());
        assertThat(options.getValue().getPattern()).isEqualTo("CHAT:*");
    }

    @Test
    void redisRemainsDisallowedAsAConfiguredSessionBackend() {
        var properties = new com.tianji.aigc.config.ChatMemoryStorageProperties();
        properties.setType("redis");
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        }
    }

    @Test
    void unknownConversationHasNoHistory() {
        assertThat(repository.findByConversationId("missing")).isEmpty();
    }

    @Test
    void stoppedMetadataSurvivesRedisSerializationAndLaterMemoryUpdates() {
        ChatMemory history = MessageWindowChatMemory.builder().chatMemoryRepository(repository).build();
        history.add("42_stopped", new org.springframework.ai.chat.messages.UserMessage("question"));
        history.add("42_stopped", AssistantMessage.builder().content("partial")
                .properties(Map.of(MessageUtil.GENERATION_STATUS, MessageUtil.STOPPED)).build());
        assertThat(history.get("42_stopped")).hasSize(2);
        assertThat(MessageUtil.isStopped(history.get("42_stopped").get(1))).isTrue();
        history.add("42_stopped", new org.springframework.ai.chat.messages.UserMessage("next"));
        assertThat(history.get("42_stopped")).extracting(Message::getText)
                .containsExactly("question", "partial", "next");
    }

    @Test
    void nextStreamingRequestReceivesOrderedHistoryAndOtherConversationsStaySeparate() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("no")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("ted"))))));
        ChatMemory memory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).build();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();

        send(client, "42_session-a", "remember maple-42");
        send(client, "42_session-a", "what is the marker?");
        send(client, "42_session-b", "another question");

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).stream(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions())
                .filteredOn(message -> message.getMessageType() != MessageType.SYSTEM)
                .extracting(Message::getText)
                .containsExactly("remember maple-42", "noted", "what is the marker?");
        assertThat(prompts.getAllValues().get(2).getInstructions())
                .filteredOn(message -> message.getMessageType() != MessageType.SYSTEM)
                .extracting(Message::getText)
                .containsExactly("another question");
        assertThat(repository.findByConversationId("42_session-a"))
                .extracting(Message::getText)
                .containsExactly("remember maple-42", "noted", "what is the marker?", "noted");
    }

    private void send(ChatClient client, String conversationId, String question) {
        List<String> chunks = client.prompt().system("test system").user(question)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream().content().collectList().block(Duration.ofSeconds(5));
        assertThat(chunks).containsExactly("no", "ted");
    }
}
