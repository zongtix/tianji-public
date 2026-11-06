package com.tianji.aigc.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.enums.MessageTypeEnum;
import com.tianji.aigc.mapper.ChatSessionMapper;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.memory.MongoMemoryCleanup;
import com.tianji.aigc.longterm.UserMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatGenerationStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.MessageVO;
import com.tianji.aigc.vo.SessionVO;
import com.tianji.aigc.vo.HistorySessionVO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.util.StringUtils;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {

    private final SessionProperties sessionProperties;

    private final ChatMemory chatMemory;
    private final KnowledgeBaseAccess knowledgeBaseAccess;
    private final ChatGenerationStore generationStore;
    private final PlatformTransactionManager transactionManager;
    private MongoMemoryCleanup mongoMemoryCleanup;
    private UserMemoryStore userMemory;

    @Autowired(required = false)
    void setMongoMemoryCleanup(MongoMemoryCleanup cleanup) {
        this.mongoMemoryCleanup = cleanup;
    }

    @Autowired(required = false)
    void setUserMemory(UserMemoryStore userMemory) { this.userMemory = userMemory; }

    @Override
    public SessionVO createSession(Integer num, String knowledgeBaseId) {
        Long userId = requireUser();
        knowledgeBaseAccess.requireFilter(knowledgeBaseId);
        var sessionVO = BeanUtil.toBean(sessionProperties, SessionVO.class);
        // 随机获取examples
        sessionVO.setExamples(getHotQuestions(num));

        // 随机生成sessionId
        sessionVO.setSessionId(IdUtil.fastSimpleUUID());
        sessionVO.setKnowledgeBaseId(knowledgeBaseId);

        // 构建持久化对象，并持久化
        var chatSession = ChatSession.builder()
                .sessionId(sessionVO.getSessionId())
                .userId(userId)
                .titleStatus(0)
                .knowledgeBaseId(knowledgeBaseId)
                .build();
        super.save(chatSession);

        return sessionVO;
    }

    private Long requireUser() {
        Long userId = UserContext.getUser();
        if (userId == null) throw new UnauthorizedException("请先登录");
        return userId;
    }

    @Override
    public PageDTO<HistorySessionVO> queryHistory(PageQuery query) {
        // 排序由服务固定，不能将用户提供的排序字段直接带入 SQL。
        Page<ChatSession> page = lambdaQuery().eq(ChatSession::getUserId, requireUser())
                .orderByDesc(ChatSession::getUpdateTime).orderByDesc(ChatSession::getId)
                .page(new Page<>(query.getPageNo(), Math.min(query.getPageSize(), 50)));
        return PageDTO.of(page, HistorySessionVO.class);
    }

    @Override
    public ChatSession requireOwnedSession(String sessionId) {
        return requireOwnedSession(sessionId, requireUser());
    }

    @Override
    public ChatSession requireOwnedSession(String sessionId, Long userId) {
        if (userId == null) throw new UnauthorizedException("请先登录");
        if (!StringUtils.hasText(sessionId) || sessionId.length() > 32) {
            throw new BadRequestException("会话不存在或无权访问");
        }
        ChatSession session = lambdaQuery().eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, userId).one();
        if (session == null) throw new BadRequestException("会话不存在或无权访问");
        return session;
    }

    @Override
    public void renameSession(String sessionId, String title) {
        ChatSession session = requireOwnedSession(sessionId);
        String normalized = title == null ? "" : title.strip();
        if (normalized.isEmpty() || normalized.length() > 40) {
            throw new BadRequestException("标题不能为空且不能超过40字");
        }
        if (!lambdaUpdate().eq(ChatSession::getId, session.getId())
                .eq(ChatSession::getUserId, session.getUserId())
                .set(ChatSession::getTitle, normalized)
                .set(ChatSession::getTitleStatus, 3)
                .setSql("update_time = update_time").update()) {
            throw new BadRequestException("会话不存在或无权访问");
        }
    }

    @Override
    public boolean claimFirstTurnTitle(String sessionId, Long userId) {
        return lambdaUpdate().eq(ChatSession::getSessionId, sessionId).eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getTitleStatus, 0).set(ChatSession::getTitleStatus, 1)
                .setSql("update_time = update_time").update();
    }

    @Override
    public void saveGeneratedTitle(String sessionId, Long userId, String title) {
        if (!StringUtils.hasText(title) || title.length() > 40) throw new BadRequestException("标题格式不正确");
        // 手动改名、删除或已生成时均不写入；状态判断与更新在同一条 SQL 中完成。
        lambdaUpdate().eq(ChatSession::getSessionId, sessionId).eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getTitleStatus, 1).set(ChatSession::getTitle, title)
                .set(ChatSession::getTitleStatus, 2).setSql("update_time = update_time").update();
    }

    @Override
    public void deleteSession(String sessionId) {
        ChatSession session = requireOwnedSession(sessionId);
        // 与生成共用占位：正在生成（包括停止后的补存阶段）不能删除。
        var lease = generationStore.acquire(sessionId);
        try {
            // 必须在事务提交后才释放占位；MySQL 消息存储会加入同一个事务。
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                requireOwnedSession(sessionId, session.getUserId());
                String conversationId = ChatService.conversationId(session.getUserId(), sessionId);
                if (userMemory != null) userMemory.deletePendingForSession(session.getUserId(), sessionId);
                if (mongoMemoryCleanup == null) {
                    chatMemory.clear(conversationId);
                } else {
                    mongoMemoryCleanup.enqueue(conversationId);
                }
                if (!lambdaUpdate().eq(ChatSession::getId, session.getId())
                        .eq(ChatSession::getUserId, session.getUserId()).remove()) {
                    throw new BadRequestException("会话不存在或无权访问");
                }
            });
        } finally {
            try {
                generationStore.release(lease);
            } catch (RuntimeException error) {
                log.warn("释放会话删除占位失败，等待租约过期", error);
            }
        }
    }

    @Override
    public void prepareChat(String sessionId, String question) {
        ChatSession session = requireOwnedSession(sessionId);
        if (!StringUtils.hasText(question) || question.length() > 3000) {
            throw new BadRequestException("问题不能为空且不能超过3000字");
        }
        lambdaUpdate().eq(ChatSession::getId, session.getId())
                .eq(ChatSession::getUserId, session.getUserId())
                .set(ChatSession::getUpdateTime, LocalDateTime.now()).update();
        if (!StringUtils.hasText(session.getTitle())) {
            String title = question.strip();
            // 自动标题仅填空值，避免并发首问覆盖用户刚修改的标题。
            lambdaUpdate().eq(ChatSession::getId, session.getId())
                    .eq(ChatSession::getUserId, session.getUserId())
                    .and(q -> q.isNull(ChatSession::getTitle).or().eq(ChatSession::getTitle, ""))
                    .set(ChatSession::getTitle, title.substring(0, Math.min(title.length(), 40))).update();
        }
    }

    @Override
    public List<SessionVO.Example> getHotQuestions(Integer num) {
        return RandomUtil.randomEleList(sessionProperties.getExamples(), num);
    }

    private Map<String, Object> messageParams(Message message) {
        Map<String, Object> params = new HashMap<>();
        if (MessageUtil.isStopped(message)) {
            params.put(MessageUtil.GENERATION_STATUS, MessageUtil.STOPPED);
        }
        if (message.getMessageType() == MessageType.ASSISTANT
                && message.getMetadata().containsKey(MessageUtil.COURSE_CARDS)) {
            params.put(MessageUtil.COURSE_CARDS, message.getMetadata().get(MessageUtil.COURSE_CARDS));
        }
        return params;
    }

    @Override
    public List<MessageVO> queryBySessionId(String sessionId) {
        ChatSession session = requireOwnedSession(sessionId);
        String conversationId = ChatService.conversationId(session.getUserId(), sessionId);
        // 从配置选中的消息存储中获取历史消息
        List<Message> messageList = this.chatMemory.get(conversationId);
        // 过滤并转换消息列表
        return StreamUtil.of(messageList)
                // 过滤掉非用户消息和助手消息
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT || message.getMessageType() == MessageType.USER)
                // 转换为MessageVO对象
                .map(message -> MessageVO.builder()
                        .content(message.getText())
                        .type(MessageTypeEnum.valueOf(message.getMessageType().name()))
                        .params(messageParams(message))
                        .build())
                .toList();
    }

}
