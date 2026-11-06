package com.tianji.aigc.service;

import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import reactor.core.publisher.Flux;

public interface ChatService {
    Flux<ChatEventVO> chat(String question, String sessionId);

    Flux<ChatEventVO> chat(String question, String sessionId, boolean stopAcknowledgement);

    void stop(String sessionId);

    boolean stop(String sessionId, String requestId);

    /**
     * 获取对话id，规则：用户id_会话id
     *
     * @param sessionId 会话id
     * @return 对话id
     */
    static String getConversationId(String sessionId) {
        return conversationId(UserContext.getUser(), sessionId);
    }

    static String conversationId(Long userId, String sessionId) {
        return userId + "_" + sessionId;
    }
}
