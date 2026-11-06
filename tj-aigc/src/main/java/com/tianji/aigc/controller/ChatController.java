package com.tianji.aigc.controller;

import com.tianji.aigc.dto.ChatDTO;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.aigc.service.BotTextService;
import com.tianji.api.dto.aigc.BotReplyRequest;
import com.tianji.api.client.aigc.BotReplyClient;
import com.tianji.common.annotations.NoWrapper;
import com.tianji.common.exceptions.ForbiddenException;
import com.tianji.common.utils.WebUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@NoWrapper
@RequestMapping("chat")
@Slf4j
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;
    private final ChatSessionService chatSessionService;
    private final BotTextService botTextService;

    @Value("${tj.ai.internal-token:${TJ_AI_INTERNAL_TOKEN:}}")
    private String internalToken = "";

    @PostMapping(value = "/reply", produces = "text/plain;charset=UTF-8")
    public String reply(@Valid @RequestBody BotReplyRequest request) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (!WebUtils.isFeignRequest()) throw new ForbiddenException("该接口仅允许内部服务调用");
        String supplied = WebUtils.getHeader(BotReplyClient.INTERNAL_TOKEN_HEADER);
        if (!StringUtils.hasText(internalToken) || !StringUtils.hasText(supplied)
                || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("内部服务凭据无效");
        }
        return botTextService.reply(request);
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEventVO> chat(@RequestBody ChatDTO chatDTO) {
        chatSessionService.prepareChat(chatDTO.getSessionId(), chatDTO.getQuestion());
        return chatDTO.isStopAcknowledgement()
                ? chatService.chat(chatDTO.getQuestion(), chatDTO.getSessionId(), true)
                : chatService.chat(chatDTO.getQuestion(), chatDTO.getSessionId());
    }

    @PostMapping("/stop")
    public void stop(@RequestParam("sessionId") String sessionId,
                     @RequestParam(value = "requestId", required = false) String requestId) {
        chatSessionService.requireOwnedSession(sessionId);
        if (requestId == null) {
            this.chatService.stop(sessionId);
        } else if (!this.chatService.stop(sessionId, requestId)) {
            throw new com.tianji.common.exceptions.BadRequestException("该轮生成已结束，无法停止");
        }
    }

    public void stop(String sessionId) {
        stop(sessionId, null);
    }

}
