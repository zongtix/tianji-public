package com.tianji.aigc.memory;

import com.tianji.aigc.mapper.ChatMemoryMessageMapper;
import com.tianji.aigc.longterm.UserMemoryCompletionAdvisor;
import com.tianji.aigc.longterm.UserMemoryStore;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.util.List;

/** saveAll 接收完整的记忆窗口，以事务替换旧消息，避免重复追加或保存失败后丢失历史。 */
public class MysqlChatMemoryRepository implements ChatMemoryRepository {
    private static final Logger log = LoggerFactory.getLogger(MysqlChatMemoryRepository.class);
    private final ChatMemoryMessageMapper mapper;
    private final TransactionTemplate transaction;
    private final UserMemoryStore userMemory;

    public MysqlChatMemoryRepository(ChatMemoryMessageMapper mapper, PlatformTransactionManager transactionManager) {
        this(mapper, transactionManager, null);
    }

    public MysqlChatMemoryRepository(ChatMemoryMessageMapper mapper, PlatformTransactionManager transactionManager,
                                     UserMemoryStore userMemory) {
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.userMemory = userMemory;
    }

    @Override
    public List<String> findConversationIds() {
        return mapper.findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "对话ID不能为空");
        return mapper.findMessages(conversationId).stream().map(MessageUtil::toMessage).toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "对话ID不能为空");
        Assert.notNull(messages, "消息列表不能为null");
        List<String> serialized = messages.stream().map(MessageUtil::toJson).toList();
        Object marker = messages.isEmpty() || !(messages.get(messages.size() - 1) instanceof AssistantMessage assistant)
                ? null : assistant.getMetadata().get(UserMemoryCompletionAdvisor.REQUEST_ID);
        try {
            transaction.executeWithoutResult(status -> {
                saveWindow(conversationId, serialized);
                if (userMemory != null && marker instanceof String requestId) {
                    try {
                        if (!userMemory.markPersisted(requestId, conversationId)) {
                            // 可能是撤回、关闭记忆或 epoch 变化；保留聊天，不恢复已失效的任务。
                            log.warn("长期记忆交接未接受 PERSISTED，本轮跳过提取, requestRef={}",
                                    Integer.toHexString(requestId.hashCode()));
                        }
                    }
                    catch (RuntimeException error) { throw new UserMemoryHandoffFailure(error); }
                }
            });
        } catch (UserMemoryHandoffFailure error) {
            // 长期记忆交接失败不阻塞聊天：回滚联合事务后只重试原会话消息保存。
            log.warn("长期记忆交接失败，本轮跳过提取: {}", error.getCause().getClass().getSimpleName());
            transaction.executeWithoutResult(status -> saveWindow(conversationId, serialized));
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "对话ID不能为空");
        mapper.deleteMessages(conversationId);
    }

    private void saveWindow(String conversationId, List<String> serialized) {
        mapper.deleteMessages(conversationId);
        if (!serialized.isEmpty()) mapper.insertMessages(conversationId, serialized);
    }

    private static final class UserMemoryHandoffFailure extends RuntimeException {
        UserMemoryHandoffFailure(Throwable cause) { super(cause); }
    }
}
