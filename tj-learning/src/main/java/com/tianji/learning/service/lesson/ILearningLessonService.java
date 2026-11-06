package com.tianji.learning.service.lesson;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;

import java.util.List;

/**
 * <p>
 * 学生课程表 服务类
 * </p>
 */
public interface ILearningLessonService extends IService<LearningLesson> {

    void addUserLessons(Long userId, List<Long> courseIds);

    void deleteUserLessons(Long userId, List<Long> courseIds);

    PageDTO<LearningLessonVO> queryMyLessons(PageQuery query);

    LearningLessonVO queryMyCurrentLesson();

    LearningLessonVO queryLessonByCourseId(Long courseId);

    void deleteCourseFromLesson(Long courseId);

    Long isLessonValid(Long courseId);

    Integer countLearningLessonByCourse(Long courseId);

    LearningLesson queryByUserIdAndCourseId(Long userId,Long courseId);

    /**
     * 创建或修改学习计划：写入每周学习频率，并把「没有计划」的课表流转为「计划进行中」
     *
     * @param courseId 课程id
     * @param freq     每周学习频率
     */
    void createLearningPlan(Long courseId, Integer freq);

    /**
     * 分页查询「我的学习计划」。
     *
     * <p>返回的不只是列表，还带了三个全局统计（本周完成 / 本周计划 / 本周积分），
     * 因为前端个人中心那两个数字跟当前页展示哪几条无关，必须整体算一次。
     *
     * <p>列表范围：只有「有计划(plan_status=1)」且「未结束(status=0/1)」的课才会出现。
     *
     * @param query 分页参数（页码、每页条数、排序）。由 {@code PageQuery.toMpPage(...)} 转成 MP 的 Page
     * @return 分页结果 + 本周统计
     */
    LearningPlanPageVO queryMyPlans(PageQuery query);

}
