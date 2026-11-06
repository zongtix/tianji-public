package com.tianji.learning.controller.interaction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.service.interaction.IInteractionQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 互动问答 —— 提问（用户端）相关接口
 *
 * <p>本 Controller 待实现的接口（day05 讲义「设计业务接口」）：
 * <pre>
 *  POST   /questions          新增互动问题
 *  PUT    /questions/{id}     修改互动问题
 *  GET    /questions/page     分页查询问题（用户端）
 *  GET    /questions/{id}     根据 id 查询问题详情（用户端）
 *  DELETE /questions/{id}     删除我的问题
 * </pre>
 *
 * <p>⚠️ {@code /questions/page} 与 {@code /questions/{id}} 同在一个 Controller 里，
 * Spring 的路径匹配会优先命中固定路径 {@code /page}，所以 {@code page} 不会被
 * 当成 id 去解析。但反过来说，以后要是新增了别的「纯单词」子路径（如 /questions/plans），
 * 记得它不会被 {@code {id}} 吃掉，别照抄 day03 那个 {@code NumberFormatException} 的排查思路。
 */
@RequiredArgsConstructor
@RestController
@Tag(name = "互动问答相关接口")
@RequestMapping("/questions")
@Slf4j
@Validated
public class InteractionQuestionController {

    private final IInteractionQuestionService questionService;

    @Operation(summary = "新增问题")
    @PostMapping
    public void saveQuestion(@Validated @RequestBody QuestionFormDTO questionFormDTO){
        questionService.saveQuestion(questionFormDTO);
    }

    @Operation(summary = "分页查询问题")
    @GetMapping("/page")
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query){
        return questionService.queryQuestionPage(query);
    }

    @Operation(summary = "根据id查询问题")
    @GetMapping("/{id}")
    public QuestionVO queryQuestionById(@PathVariable Long id){
        return questionService.queryQuestionById(id);
    }
}
