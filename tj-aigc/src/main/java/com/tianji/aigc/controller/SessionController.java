package com.tianji.aigc.controller;

import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.dto.SessionTitleDTO;
import com.tianji.aigc.vo.MessageVO;
import com.tianji.aigc.vo.SessionVO;
import com.tianji.aigc.vo.HistorySessionVO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import org.springframework.validation.annotation.Validated;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService chatSessionService;

    @GetMapping
    public PageDTO<HistorySessionVO> queryHistory(@Validated PageQuery query) {
        return chatSessionService.queryHistory(query);
    }

    @PutMapping("/{sessionId}/title")
    public void renameSession(@PathVariable("sessionId") String sessionId, @RequestBody SessionTitleDTO body) {
        chatSessionService.renameSession(sessionId, body.getTitle());
    }

    @DeleteMapping("/{sessionId}")
    public void deleteSession(@PathVariable("sessionId") String sessionId) {
        chatSessionService.deleteSession(sessionId);
    }

    /**
     * 新建会话
     */
    @PostMapping
    public SessionVO createSession(@RequestParam(value = "n", defaultValue = "3") Integer num,
                                   @RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId) {
        return this.chatSessionService.createSession(num, knowledgeBaseId);
    }

    /**
     * 换一组推荐问题，不创建会话。
     */
    @GetMapping("/hot")
    public List<SessionVO.Example> getHotQuestions(@RequestParam(value = "n", defaultValue = "3") Integer num) {
        return this.chatSessionService.getHotQuestions(num);
    }

    /**
     * 查询单个历史对话详情
     *
     * @return 对话记录列表
     */
    @GetMapping("/{sessionId}")
    public List<MessageVO> queryBySessionId(@PathVariable("sessionId") String sessionId) {
        chatSessionService.requireOwnedSession(sessionId);
        return this.chatSessionService.queryBySessionId(sessionId);
    }

}
