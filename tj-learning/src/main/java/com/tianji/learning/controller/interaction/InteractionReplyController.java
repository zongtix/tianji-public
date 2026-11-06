package com.tianji.learning.controller.interaction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.ForbiddenException;
import com.tianji.common.utils.WebUtils;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.domain.vo.ReplySubmissionVO;
import com.tianji.learning.service.interaction.BotReplyService;
import com.tianji.learning.service.interaction.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

/**
 * 互动问答 —— 回答、评论（用户端）相关接口
 *
 * <p>本 Controller 当前实现的接口（day05 讲义「设计业务接口」）：
 * <pre>
 *  POST /replies        新增回答或评论
 *  GET  /replies/page   分页查询回答或评论列表（用户端）
 * </pre>
 *
 * <p>「回答」与「评论」共用同一张表。新增时不传 answerId 表示直接回答，传根回答 ID 表示评论。
 * 列表查询以数据库 answer_id=0 查根回答；Java null 如何落为该约定值需结合实际 DDL 确认。
 * 内容新增由 learning 完成，remark 的 QA 点赞只引用生成的回复 ID，不在此接口中执行。
 */
@RequiredArgsConstructor
@RestController
@Tag(name = "互动问答相关接口")
@RequestMapping("/replies")
@Slf4j
@Validated
public class InteractionReplyController {

    private final IInteractionReplyService replyService;
    private final BotReplyService botReplyService;

    @GetMapping("/bot")
    public BotReplyService.BotInfo botInfo() {
        return botReplyService.info();
    }

    @GetMapping("/bot/status")
    public BotReplyService.BotStatus botStatus(@RequestParam("sourceReplyId") Long sourceReplyId) {
        return botReplyService.status(sourceReplyId);
    }

    @Operation(summary = "批量查询存在的回答或评论id")
    @GetMapping("/exists")
    public Set<Long> queryExistingReplyIds(@RequestParam("ids") @Size(max = 500) List<Long> ids) {
        // 已有 Feign 拦截器写入来源标记；Gateway 会覆盖客户端伪造的同名标记。
        if (!WebUtils.isFeignRequest()) {
            throw new ForbiddenException("该接口仅允许内部服务调用");
        }
        return replyService.queryExistingReplyIds(ids);
    }

    @Operation(summary = "新增回答或评论")
    @PostMapping
    public ReplySubmissionVO saveReply(@Validated @RequestBody ReplyDTO replyDTO) {
        return replyService.saveReply(replyDTO);
    }

    @Operation(summary = "分页查询回答或评论")
    @GetMapping("/page")
    public PageDTO<ReplyVO> queryReplyPage(@Validated ReplyPageQuery query) {
        return replyService.queryReplyPage(query, false);
    }
}
