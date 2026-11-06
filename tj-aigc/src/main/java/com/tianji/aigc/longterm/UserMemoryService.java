package com.tianji.aigc.longterm;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.util.StringUtils;

import java.util.List;

public final class UserMemoryService {
    private final UserMemoryStore store;
    private final ChatMemory chatMemory;

    public UserMemoryService(UserMemoryStore store, ChatMemory chatMemory) {
        this.store = store;
        this.chatMemory = chatMemory;
    }

    public UserMemoryContext prepare(long userId, String sessionId, String requestId, String conversationId, String question) {
        var state = store.state(userId);
        if (!state.enabled()) return new UserMemoryContext(userId, requestId, false, List.of());
        var memories = state.memories();
        var history = chatMemory.get(conversationId);
        String previousQuestion = null;
        if (!history.isEmpty()) {
            var last = history.get(history.size() - 1);
            String text = last.getText();
            if (last.getMessageType() == MessageType.ASSISTANT && StringUtils.hasText(text)
                    && (text.strip().endsWith("？") || text.strip().endsWith("?"))) previousQuestion = text;
        }
        boolean staged = store.stage(userId, sessionId, requestId, question, previousQuestion, state.epoch());
        return new UserMemoryContext(userId, requestId, staged, memories);
    }

    public void abandon(String requestId) { store.abandon(requestId); }

    public boolean isPersisted(String requestId, String conversationId) { return store.isPersisted(requestId, conversationId); }

    public boolean markReady(String requestId, String conversationId) { return store.markReady(requestId, conversationId); }
}
