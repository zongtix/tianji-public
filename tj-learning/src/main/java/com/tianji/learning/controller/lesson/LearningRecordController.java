package com.tianji.learning.controller.lesson;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.vo.LearningRecordVO;
import com.tianji.learning.service.lesson.ILearningRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 学习记录 相关接口
 */
@RequiredArgsConstructor
@RestController
@Tag(name = "学习记录相关接口")
@RequestMapping("/learning-records")
@Slf4j
@Validated
public class LearningRecordController {

    private final ILearningRecordService learningRecordService;

    @GetMapping("/course/{courseId}")
    @Operation(summary = "查询指定课程的学习记录")
    public LearningLessonDTO queryLearningRecordByCourse(
            @Parameter(description = "课程id",example = "2")@PathVariable("courseId") Long courseId){
         return learningRecordService.queryLearningRecordByCourse(courseId);

    };

    @PostMapping
    @Operation(summary = "提交学习记录")
    public void addLearningRecord(@RequestBody @Valid LearningRecordFormDTO recordDTO){
        learningRecordService.addLearningRecord(recordDTO);
    }


}
