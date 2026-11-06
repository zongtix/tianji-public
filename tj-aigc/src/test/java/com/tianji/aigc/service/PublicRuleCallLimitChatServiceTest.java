package com.tianji.aigc.service;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.service.impl.ChatServiceImpl;
import com.tianji.aigc.tools.PublicRuleCallLimitException;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 公共规则预算耗尽：SSE ERROR+STOP，并释放生成租约。 */
class PublicRuleCallLimitChatServiceTest {
    private static final String SESSION = "public-rule-limit";

    private final ChatModel model = mock(ChatModel.class);
    private final ChatGenerationStore store = mock(ChatGenerationStore.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
    private final AtomicBoolean occupied = new AtomicBoolean();
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUser(42L);
        var generation = mock(ChatGenerationStore.Generation.class);
        when(generation.isValid()).thenReturn(true);
        when(generation.requestId()).thenReturn("req-public-rule-limit");
        when(store.acquire(any())).thenAnswer(invocation -> {
            occupied.set(true);
            return generation;
        });
        when(store.renew(any())).thenReturn(true);
        when(store.isCurrent(any())).thenReturn(true);
        when(store.complete(any())).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            occupied.set(false);
            return null;
        }).when(store).release(any());
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var client = ChatClient.builder(model)
                .defaultAdvisors(new com.tianji.aigc.config.SpringAIConfig().messageChatMemoryAdvisor(memory))
                .build();
        var prompts = mock(SystemPromptConfig.class);
        when(prompts.getChatSystemMessage()).thenReturn(new AtomicReference<>("system"));
        service = com.tianji.aigc.ChatTestSupport.ordinaryChat(client, prompts, store, memory);
    }

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @Test
    void publicRuleCallLimitEmitsErrorAndStopAndReleasesLease() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(
                new IllegalStateException("tool wrapper", new PublicRuleCallLimitException())));

        var events = service.chat("积分怎么算", SESSION).collectList().block(Duration.ofSeconds(3));

        assertThat(events).extracting(ChatEventVO::getEventType).containsExactly(1005, 1002);
        assertThat(events.get(0).getEventData().toString())
                .contains("PUBLIC_RULE_CALL_LIMIT")
                .doesNotContain("tool wrapper", "Exception", "PublicRuleCallLimitException");
        verify(store).release(any());
        assertThat(occupied).isFalse();
    }
}
