package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.KnowledgeRagProperties;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

import java.util.*;

/** cl100k_base 估算并增加安全余量；只选完整片段，不改写或截断原文。 */
public class KnowledgeContextSelector {
    private final KnowledgeRagProperties.Context settings;
    private final JTokkitTokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    public KnowledgeContextSelector(KnowledgeRagProperties.Context settings) { this.settings = settings; }

    public int estimate(String text) { return estimator.estimate(text == null ? "" : text); }

    public int requestTokens(ChatClientRequest request) {
        // 每条消息额外预估协议封装；知识库链路禁止工具，若出现工具定义则纳入预算。
        int tokens = request.prompt().getInstructions().stream().mapToInt(m -> estimate(m.getText()) + 8).sum() + 3;
        var options = request.prompt().getOptions();
        if (options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools) {
            if (tools.getToolCallbacks() != null) {
                for (var tool : tools.getToolCallbacks()) {
                    var def = tool.getToolDefinition();
                    tokens += estimate(def.name()) + estimate(def.description()) + estimate(def.inputSchema()) + 16;
                }
            }
            // 工具名称仍待解析时不能声称已计入 schema，拒绝已知不完整预算。
            if (tools.getToolNames() != null && !tools.getToolNames().isEmpty()) {
                throw new ContextTooLongException("知识库请求包含未解析的工具协议");
            }
        }
        return tokens;
    }

    private int reserved(ChatClientRequest request) {
        Integer max = request.prompt().getOptions() == null ? null : request.prompt().getOptions().getMaxTokens();
        return Math.max(settings.getOutputReserve(), max == null ? 0 : max) + settings.getSafetyMargin();
    }

    public void check(ChatClientRequest request) {
        if (requestTokens(request) + reserved(request) > settings.getWindowTokens()) {
            throw new ContextTooLongException("上下文过长，请缩短问题或新建会话后重试");
        }
    }

    public List<Document> select(List<RagCandidate> candidates, int limit,
                                 java.util.function.Function<List<Document>, ChatClientRequest> assemble) {
        check(assemble.apply(List.of()));
        List<Document> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RagCandidate candidate : candidates) {
            if (selected.size() >= limit) break;
            if (!seen.add(candidate.identity())) continue;
            selected.add(candidate.document());
            ChatClientRequest full = assemble.apply(List.copyOf(selected));
            if (requestTokens(full) + reserved(full) > settings.getWindowTokens()) {
                selected.remove(selected.size() - 1);
            }
        }
        return List.copyOf(selected);
    }

    public static boolean isContextTooLong(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ContextTooLongException) return true;
        }
        return false;
    }

    public static class ContextTooLongException extends RuntimeException {
        public ContextTooLongException(String message) { super(message); }
    }
}
