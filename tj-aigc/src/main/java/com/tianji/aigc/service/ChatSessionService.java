package com.tianji.aigc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.aigc.entity.ChatSession;
import com.tianji.aigc.vo.MessageVO;
import com.tianji.aigc.vo.SessionVO;
import com.tianji.aigc.vo.HistorySessionVO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;

import java.util.List;

public interface ChatSessionService extends IService<ChatSession> {

    /**
     * 创建会话session
     *
     * @param num 热门问题的数量
     * @return 会话信息
     */
    default SessionVO createSession(Integer num) { return createSession(num, null); }

    SessionVO createSession(Integer num, String knowledgeBaseId);

    /**
     * 随机获取推荐问题，不创建会话。
     */
    List<SessionVO.Example> getHotQuestions(Integer num);

    List<MessageVO> queryBySessionId(String sessionId);

    PageDTO<HistorySessionVO> queryHistory(PageQuery query);

    ChatSession requireOwnedSession(String sessionId);

    // 响应式调度后使用入口处已验证的用户身份，不依赖线程局部变量。
    ChatSession requireOwnedSession(String sessionId, Long userId);

    void renameSession(String sessionId, String title);

    void deleteSession(String sessionId);

    void prepareChat(String sessionId, String question);

    boolean claimFirstTurnTitle(String sessionId, Long userId);

    void saveGeneratedTitle(String sessionId, Long userId, String title);
}
