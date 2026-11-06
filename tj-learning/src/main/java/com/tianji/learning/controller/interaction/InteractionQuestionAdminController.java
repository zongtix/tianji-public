package com.tianji.learning.controller.interaction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.service.interaction.IInteractionQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动问答 —— 提问（管理端）相关接口
 *
 * <p>本 Controller 待实现的接口（day05 讲义「设计业务接口」）：
 * <pre>
 *  GET /admin/questions/page                 分页查询问题（管理端）
 *  GET /admin/questions/{id}                 根据 id 查询问题详情（管理端）
 *  PUT /admin/questions/{id}/hidden/{hidden} 隐藏或显示指定问题
 * </pre>
 *
 * <p>管理端接口与用户端分开成两个 Controller，是因为两者返回的字段不同
 * （管理端要多看提问人、隐藏状态、查看状态等信息），共用一套 VO 会把
 * 用户端不该看到的字段带出去。
 */
@RequiredArgsConstructor
@RestController
@Tag(name = "互动问答相关接口")
@RequestMapping("/admin/questions")
@Slf4j
@Validated
public class InteractionQuestionAdminController {

    private final IInteractionQuestionService questionService;

    @GetMapping("/page")
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query){
        return questionService.queryQuestionPageAdmin(query);
    }

    @Operation(summary = "管理端根据id查询互动问题")
    @GetMapping("/{id}")
    public QuestionAdminVO queryQuestionByIdAdmin(@PathVariable("id") Long id) {
        return questionService.queryQuestionByIdAdmin(id);
    }
}
