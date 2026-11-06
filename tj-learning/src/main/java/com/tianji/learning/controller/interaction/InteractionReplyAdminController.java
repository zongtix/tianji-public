package com.tianji.learning.controller.interaction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.service.interaction.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动问答 —— 回答、评论（管理端）相关接口
 *
 * <p>本 Controller 当前实现的接口（day05 讲义「设计业务接口」）：
 * <pre>
 *  GET /admin/replies/page                 分页查询回答或评论列表（管理端）
 *  PUT /admin/replies/{id}/hidden/{hidden} 隐藏或显示指定回答或评论
 * </pre>
 *
 * <p>管理端能看到被隐藏的回答（用户端不能），所以这两类接口的查询条件、
 * 返回信息与用户端存在差异；本类复用 queryReplyPage(query, true)，由 forAdmin 切换条件和组装规则。
 */
@RequiredArgsConstructor
@RestController
@Tag(name = "互动问答相关接口")
@RequestMapping("/admin/replies")
@Slf4j
@Validated
public class InteractionReplyAdminController {

    private final IInteractionReplyService replyService;

    @Operation(summary = "管理端分页查询回答或评论")
    @GetMapping("/page")
    public PageDTO<ReplyVO> queryReplyPage(@Validated ReplyPageQuery query) {
        return replyService.queryReplyPage(query, true);
    }

    @Operation(summary = "隐藏或显示回答、评论")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenReply(
            @Parameter(description = "回答或评论id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "是否隐藏", example = "true") @PathVariable("hidden") Boolean hidden) {
        replyService.hiddenReply(id, hidden);
    }

    @Operation(summary = "管理端根据id查询回答或评论")
    @GetMapping("/{id}")
    public ReplyVO queryReplyById(
            @Parameter(description = "回答或评论id", example = "1") @PathVariable("id") Long id) {
        return replyService.queryReplyById(id);
    }
}
