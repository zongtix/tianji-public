package com.tianji.aigc.agent;

import com.tianji.aigc.knowledge.KnowledgeContextSelector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatContextBudgetTest {
    @Test
    void longHistoryIsTrimmedForTheModelWithoutDeletingStoredMessages() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(100).build();
        String oldQuestion = "long past question ".repeat(600);
        String oldAnswer = "long past answer ".repeat(600);
        memory.add("session", List.of(new UserMessage(oldQuestion), new AssistantMessage(oldAnswer)));
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt sent = call.getArgument(0);
            assertThat(sent.getInstructions()).extracting(message -> message.getText())
                    .contains("system", "current question").doesNotContain(oldQuestion, oldAnswer);
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        });
        var client = ChatClient.builder(model).defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build(),
                new ChatContextBudgetAdvisor(new ChatContextBudget(600, 100, 20))).build();

        client.prompt().system("system").user("current question")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "session"))
                .stream().content().collectList().block(Duration.ofSeconds(5));

        assertThat(memory.get("session")).extracting(message -> message.getText())
                .contains(oldQuestion, oldAnswer, "current question", "answer");
        verify(model).stream(any(Prompt.class));
    }

    @Test
    void oversizedCurrentTurnIsRejectedBeforeTheModel() {
        var model = mock(ChatModel.class);
        var client = ChatClient.builder(model).defaultAdvisors(
                new ChatContextBudgetAdvisor(new ChatContextBudget(200, 50, 10))).build();
        assertThatThrownBy(() -> client.prompt().user("current question ".repeat(500))
                .stream().content().collectList().block(Duration.ofSeconds(5)))
                .satisfies(error -> assertThat(KnowledgeContextSelector.isContextTooLong(error)).isTrue());
        verify(model, never()).stream(any(Prompt.class));
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void fittingPromptKeepsTheOriginalMessagesAndOptions() {
        var prompt = new Prompt(List.of(new SystemMessage("system"), new UserMessage("question")));
        assertThat(new ChatContextBudget().fit(prompt)).isSameAs(prompt);
    }

    @Test
    void currentToolArgumentsAlsoConsumeThePromptBudget() {
        var prompt = new Prompt(List.of(new UserMessage("question"), AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "searchCourses",
                        "{\"keyword\":\"" + "large arguments ".repeat(500) + "\"}"))).build()));
        assertThatThrownBy(() -> new ChatContextBudget(300, 50, 10).fit(prompt))
                .satisfies(error -> assertThat(KnowledgeContextSelector.isContextTooLong(error)).isTrue());
    }

    @Test
    void currentToolResultsCannotBypassTheBudgetWithAnEmptyTextField() {
        var prompt = new Prompt(List.of(new UserMessage("question"), ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "searchCourses",
                        "large tool result ".repeat(500)))).build()));
        assertThatThrownBy(() -> new ChatContextBudget(300, 50, 10).fit(prompt))
                .satisfies(error -> assertThat(KnowledgeContextSelector.isContextTooLong(error)).isTrue());
    }
}
