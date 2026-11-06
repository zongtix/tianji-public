package com.tianji.aigc.agent;

import com.tianji.aigc.config.KnowledgeRagProperties;
import com.tianji.aigc.knowledge.KnowledgeContextSelector;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 仅缩减本次送模的旧轮次，不删除持久化历史，不拆开当前轮的工具协议。 */
public final class ChatContextBudget {
    private final KnowledgeContextSelector selector;
    private final int window;
    private final int outputReserve;
    private final int safetyMargin;

    public ChatContextBudget() { this(16384, 2048, 512); }

    public ChatContextBudget(int window, int outputReserve, int safetyMargin) {
        Assert.isTrue(window > 0 && outputReserve >= 0 && safetyMargin >= 0
                && (long) outputReserve + safetyMargin < window, "聊天上下文预算配置无效");
        var settings = new KnowledgeRagProperties.Context();
        settings.setWindowTokens(window);
        settings.setOutputReserve(outputReserve);
        settings.setSafetyMargin(safetyMargin);
        selector = new KnowledgeContextSelector(settings);
        this.window = window;
        this.outputReserve = outputReserve;
        this.safetyMargin = safetyMargin;
    }

    public void check(Prompt prompt) {
        var request = new ChatClientRequest(prompt, Map.of());
        long input = selector.requestTokens(request);
        for (Message message : prompt.getInstructions()) {
            if (message instanceof AssistantMessage assistant) {
                for (var call : assistant.getToolCalls()) {
                    input += selector.estimate(call.id()) + selector.estimate(call.name())
                            + selector.estimate(call.arguments()) + 16L;
                }
            }
            if (message instanceof ToolResponseMessage responses) {
                long protocol = 0;
                for (var response : responses.getResponses()) {
                    protocol += selector.estimate(response.id()) + selector.estimate(response.name())
                            + selector.estimate(response.responseData()) + 16L;
                }
                input += Math.max(0, protocol - selector.estimate(message.getText()));
            }
        }
        Integer configuredOutput = prompt.getOptions() == null ? null : prompt.getOptions().getMaxTokens();
        long reserve = Math.max(outputReserve, configuredOutput == null ? 0 : configuredOutput) + (long) safetyMargin;
        if (input + reserve > window) {
            throw new KnowledgeContextSelector.ContextTooLongException("上下文过长，请缩短问题或新建会话后重试");
        }
    }

    public Prompt fit(Prompt prompt) {
        Prompt current = prompt;
        while (true) {
            try {
                check(current);
                return current;
            } catch (KnowledgeContextSelector.ContextTooLongException error) {
                List<Message> messages = current.getInstructions();
                int firstUser = -1;
                int nextUser = -1;
                for (int i = 0; i < messages.size(); i++) {
                    if (!(messages.get(i) instanceof UserMessage)) continue;
                    if (firstUser < 0) firstUser = i;
                    else { nextUser = i; break; }
                }
                if (nextUser < 0) throw error;
                List<Message> reduced = new ArrayList<>();
                for (int i = 0; i < nextUser; i++) {
                    if (messages.get(i) instanceof SystemMessage) reduced.add(messages.get(i));
                }
                reduced.addAll(messages.subList(nextUser, messages.size()));
                current = new Prompt(reduced, current.getOptions());
            }
        }
    }
}
