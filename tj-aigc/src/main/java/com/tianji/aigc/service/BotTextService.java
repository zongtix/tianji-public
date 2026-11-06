package com.tianji.aigc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.dto.aigc.BotReplyRequest;
import com.tianji.aigc.agent.ChatContextBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 复用现有 DeepSeek ChatModel；不经过会话记忆、RAG、下单工具或路由工作流。 */
@Service
public class BotTextService {
    private final String systemPrompt;
    private final ChatModel model;
    private final ObjectMapper json;
    private final ChatContextBudget budget;

    public BotTextService(@Lazy ChatModel model, ObjectMapper json,
                          @Value("classpath:prompts/bot-system-message.txt") Resource prompt) throws IOException {
        this(model, json, prompt, new ChatContextBudget());
    }

    @Autowired
    public BotTextService(@Lazy ChatModel model, ObjectMapper json,
                          @Value("classpath:prompts/bot-system-message.txt") Resource prompt,
                          ChatContextBudget budget) throws IOException {
        this.model = model;
        this.json = json;
        this.budget = budget;
        this.systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
        if (!StringUtils.hasText(systemPrompt)) throw new IllegalStateException("机器人系统提示词不能为空");
    }

    public String reply(BotReplyRequest request) throws JsonProcessingException {
        var options = OpenAiChatOptions.builder().maxTokens(768)
                .toolChoice("none").internalToolExecutionEnabled(false)
                .extraBody(Map.of("thinking", Map.of("type", "disabled"))).build();
        var prompt = new Prompt(List.of(new SystemMessage(systemPrompt),
                new UserMessage(json.writeValueAsString(request))), options);
        budget.check(prompt);
        String text = model.stream(prompt).map(response -> {
                    if (response.getResult() == null) return "";
                    var output = response.getResult().getOutput();
                    if (!output.getToolCalls().isEmpty()) throw new IllegalStateException("机器人回复禁止工具调用");
                    return output.getText() == null ? "" : output.getText();
                })
                .reduce(new StringBuilder(), (result, chunk) -> {
                    if (result.length() + chunk.length() > 8000) throw new IllegalStateException("机器人回复过长");
                    return result.append(chunk);
                })
                .map(StringBuilder::toString).timeout(Duration.ofSeconds(30)).block();
        if (!StringUtils.hasText(text)) throw new IllegalStateException("机器人返回空回复");
        return text.strip();
    }
}
