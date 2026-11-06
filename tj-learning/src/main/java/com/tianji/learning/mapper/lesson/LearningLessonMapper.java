package com.tianji.learning.mapper.lesson;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.learning.domain.po.LearningLesson;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * <p>
 * 学生课程表 Mapper 接口
 * </p>
 */
public interface LearningLessonMapper extends BaseMapper<LearningLesson> {

    /**
     * 统计某个用户「本周计划学习的小节总数」= 本周有效计划课程的 week_freq 之和。
     *
     * <p>为什么不能直接用 MP 的 LambdaQuery 写：这是 SUM 聚合，selectCount / selectList 都表达不了，
     * 只能自己写 SQL。SQL 正文在 resources/mapper/LearningLessonMapper.xml。
     *
     * <p>「本周有效」的判定要点（XML 里有完整注释）：<b>不能只按 {@code status IN (0,1)} 过滤</b>。
     * 那样一门课整门学完（status 变 2）时会从分母里掉出去，本周计划量凭空缩水，
     * 用户看到的是「越学越少」。正确口径是：仍在进行中的课 + 本周内完成过的课。
     *
     * <p>⚠️ 返回 Integer 而不是 int：一门计划都没有时 SUM 的结果是 NULL，XML 里套了
     * IFNULL(...,0) 兜底。如果这里写 int，遇到 null 会直接 NPE。
     *
     * @param userId 学员id（注意不是课程id）
     * @param begin  本周一 00:00:00（含）—— 用来判断「这门课本周有没有完成过」
     * @param end    下周一 00:00:00（不含）
     * @return 本周计划学习的小节总数；没有任何计划时返回 0
     */
    Integer queryTotalPlan(@Param("userId") Long userId,
                           @Param("begin") LocalDateTime begin,
                           @Param("end") LocalDateTime end);
}
