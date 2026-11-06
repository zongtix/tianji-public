package com.tianji.api.client.learning;

import com.tianji.api.client.learning.fallback.LearningClientFallback;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

@FeignClient(value = "learning-service", fallbackFactory = LearningClientFallback.class)
public interface LearningClient {

    /**
     * remark 的 LikeTargetService 用此接口批量确认 QA 对象仍存在，返回存在 ID 子集。
     * learning 只投影主键，无需返回回复全文；查询失败必须与空集合（确实不存在）区分。
     */
    @GetMapping("/replies/exists")
    Set<Long> queryExistingReplyIds(@RequestParam("ids") List<Long> ids);

    /**
     * 统计课程学习人数
     * @param courseId 课程id
     * @return 学习人数
     */
    @GetMapping("/lessons/{courseId}/count")
    Integer countLearningLessonByCourse(@PathVariable("courseId") Long courseId);

    /**
     * 校验当前用户是否可以学习当前课程
     * @param courseId 课程id
     * @return lessonId，如果是报名了则返回lessonId，否则返回空
     */
    @GetMapping("/lessons/{courseId}/valid")
    Long isLessonValid(@PathVariable("courseId") Long courseId);

    /**
     * 查询当前用户指定课程的学习进度
     * @param courseId 课程id
     * @return 课表信息、学习记录及进度信息
     */
    @GetMapping("/learning-records/course/{courseId}")
    LearningLessonDTO queryLearningRecordByCourse(@PathVariable("courseId") Long courseId);

}
