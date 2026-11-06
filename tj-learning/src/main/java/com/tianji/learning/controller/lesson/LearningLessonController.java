package com.tianji.learning.controller.lesson;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.dto.LearningPlanDTO;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.service.lesson.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 学生课程表 相关接口
 */
@RequiredArgsConstructor
@RestController
@Tag(name = "我的课表相关接口")
@RequestMapping("/lessons")
@Slf4j
@Validated
public class LearningLessonController {

    private final ILearningLessonService learningLessonService;

    @GetMapping("/page")
    @Operation(summary = "分页查询我的课表")
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {
        return learningLessonService.queryMyLessons(query);
    }

    @GetMapping("/now")
    @Operation(summary = "查询我最近正在学习的课程")
    public LearningLessonVO queryMyCurrentLesson() {
        return learningLessonService.queryMyCurrentLesson();
    }

    @GetMapping("/{courseId}")
    @Operation(summary = "根据id查询指定课程的学习状态")
    public LearningLessonVO queryLessonByCourseId(
            @Parameter(description = "课程id") @PathVariable("courseId") Long courseId) {
        return learningLessonService.queryLessonByCourseId(courseId);
    }

    @DeleteMapping("/{courseId}")
    @Operation(summary = "删除课表中的某课程")
    public void deleteCourseFromLesson(
            @Parameter(description = "课程id") @PathVariable("courseId") Long courseId) {
        learningLessonService.deleteCourseFromLesson(courseId);
    }

    @GetMapping("/{courseId}/valid")
    @Operation(summary = "校验指定课程是否是课表中的有效课程")
    public Long isLessonValid(@Parameter(description = "课程id") @PathVariable("courseId") Long courseId) {
        return learningLessonService.isLessonValid(courseId);
    }

    @GetMapping("/{courseId}/count")
    @Operation(summary = "统计课程学习人数")
    public Integer countLearningLessonByCourse(
            @Parameter(description = "课程id") @PathVariable("courseId") Long courseId) {
        return learningLessonService.countLearningLessonByCourse(courseId);
    }

    @PostMapping("/plans")
    @Operation(summary = "创建学习计划")
    public void createLearningPlans(@RequestBody @Valid LearningPlanDTO planDTO) {
        learningLessonService.createLearningPlan(planDTO.getCourseId(), planDTO.getFreq());
    }

    /**
     * 查询我的学习计划（个人中心那份表格）
     *
     * <p>⚠️ 注意这里和上面那个「创建学习计划」是**同一个 URL、不同请求方法**：
     * GET /lessons/plans 查、POST /lessons/plans 建。Spring 靠 method 区分，不冲突。
     *
     * <p>⚠️ 本类还有 {@code @GetMapping("/{courseId}")}。当 {@code courseId} 是数字时没问题，
     * 但如果你把这条 /plans 映射删掉/写错，前端打 /lessons/plans 就会落进 /{courseId}，
     * 报 {@code NumberFormatException: For input string: "plans"} —— 看到这个报错，
     * 说明是「路径没映射上」，不是参数解析的 bug。
     */
    @GetMapping("/plans")
    @Operation(summary = "查询我的学习计划")
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        return learningLessonService.queryMyPlans(query);
    }
}
