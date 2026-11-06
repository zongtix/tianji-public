package com.tianji.aigc.knowledge;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 版本对应实际模板，保留旧版供固定实验对照和回退。 */
public final class KnowledgeAnswerPrompt {
    public static final String LEGACY = "knowledge-v1";
    public static final String CONDITIONS = "knowledge-v2-conditions";
    public static final String EVIDENCE = "knowledge-v3-evidence";
    public static final String FOCUSED = "knowledge-v4-focused";
    public static final String PRECISE = "knowledge-v5-precise";

    private KnowledgeAnswerPrompt() { }

    public static PromptTemplate create(String version) {
        String resource = switch (version) {
            case LEGACY -> "prompts/knowledge-retrieval-v1.txt";
            case CONDITIONS -> "prompts/knowledge-retrieval-v2-conditions.txt";
            case EVIDENCE -> "prompts/knowledge-retrieval-v3-evidence.txt";
            case FOCUSED -> "prompts/knowledge-retrieval-v4-focused.txt";
            case PRECISE -> "prompts/knowledge-retrieval-v5-precise.txt";
            default -> throw new IllegalArgumentException("未知的知识库回答提示词版本");
        };
        try {
            return new PromptTemplate(new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)
                    .replace("\r\n", "\n"));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取知识库回答提示词", e);
        }
    }
}
