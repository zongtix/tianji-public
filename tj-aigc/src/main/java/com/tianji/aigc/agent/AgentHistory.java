package com.tianji.aigc.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.memory.MessageUtil;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 给路由提供有限的只读历史；卡片只提供定位信息，价格等事实仍须重新查询。 */
final class AgentHistory {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_MESSAGES = 6;
    private static final int MAX_TEXT_CHARS = 4000;

    private AgentHistory() { }

    static Snapshot recent(List<Message> messages) {
        List<Turn> turns = new ArrayList<>();
        int remaining = MAX_TEXT_CHARS;
        boolean truncated = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getMessageType() != MessageType.USER
                    && message.getMessageType() != MessageType.ASSISTANT) continue;
            if (turns.size() == MAX_MESSAGES || remaining == 0) {
                truncated = true;
                break;
            }
            String text = message.getText() == null ? "" : message.getText();
            if (text.length() > remaining) {
                text = text.substring(0, remaining);
                truncated = true;
            }
            remaining -= text.length();
            turns.add(new Turn(message.getMessageType().name(), text, cards(message)));
        }
        Collections.reverse(turns);
        return new Snapshot(turns, truncated);
    }

    static List<CourseReference> latestCards(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            List<CourseReference> cards = cards(messages.get(i));
            if (!cards.isEmpty()) return cards;
        }
        return List.of();
    }

    private static List<CourseReference> cards(Message message) {
        if (message.getMessageType() != MessageType.ASSISTANT) return List.of();
        Object metadata = message.getMetadata().get(MessageUtil.COURSE_CARDS);
        if (metadata == null) return List.of();
        JsonNode values = JSON.valueToTree(metadata);
        if (!values.isArray()) return List.of();
        List<CourseReference> result = new ArrayList<>();
        for (JsonNode value : values) {
            String id = value.path("courseId").asText("");
            String name = value.path("name").asText("");
            if (!id.matches("[1-9][0-9]{0,18}") || name.isBlank()) continue;
            result.add(new CourseReference(id, name.substring(0, Math.min(name.length(), 100))));
            if (result.size() == 3) break;
        }
        return result;
    }

    record Snapshot(List<Turn> history, boolean historyTruncated) { }
    record Turn(String role, String text, List<CourseReference> courses) { }
    record CourseReference(String courseId, String name) { }
}
