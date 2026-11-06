package com.tianji.aigc.memory;

import org.bson.Document;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.util.Assert;

import java.util.List;

/** 一个对话对应一个文档，消息数组按顺序保存；单文档更新整体替换记忆窗口。 */
public class MongoChatMemoryRepository implements ChatMemoryRepository {
    public static final String COLLECTION = "chat_memory";
    private final MongoTemplate template;

    public MongoChatMemoryRepository(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public List<String> findConversationIds() {
        Query query = new Query();
        query.fields().include("_id");
        return template.find(query, Document.class, COLLECTION).stream()
                .map(document -> document.getString("_id")).toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Document document = template.findOne(byId(conversationId), Document.class, COLLECTION);
        if (document == null) return List.of();
        return document.getList("messages", String.class).stream().map(MessageUtil::toMessage).toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "对话ID不能为空");
        Assert.notNull(messages, "消息列表不能为null");
        List<String> serialized = messages.stream().map(MessageUtil::toJson).toList();
        if (serialized.isEmpty()) {
            deleteByConversationId(conversationId);
            return;
        }
        template.upsert(byId(conversationId), new Update().set("messages", serialized), COLLECTION);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        template.remove(byId(conversationId), COLLECTION);
    }

    private Query byId(String conversationId) {
        Assert.hasText(conversationId, "对话ID不能为空");
        // 保持 ID 为字符串；不能让看起来像 ObjectId 的字符串发生隐式转换。
        return Query.query(Criteria.where("_id").is(conversationId));
    }
}
