package com.tianji.learning.mapper.lesson;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.api.dto.IdAndNumDTO;
import com.tianji.learning.domain.po.LearningRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 学习记录表 Mapper 接口
 * </p>
 */
public interface LearningRecordMapper extends BaseMapper<LearningRecord> {

    /**
     * 按课表id分组，统计某用户「本周内完成学习」的小节数量。
     *
     * <p>为什么一次查出来分组、而不是在循环里逐个 lessonId 查：
     * 一页 20 条课就是 20 次数据库往返（典型的 N+1）。一次 GROUP BY 查回来，
     * 在内存里按 lessonId 取用，SQL 次数从 N 降到 1。
     *
     * <p>返回 {@link IdAndNumDTO}（id + num 的通用模型，定义在 tj-api 里），
     * 可以直接用 {@code IdAndNumDTO.toMap(list)} 转成 Map&lt;lessonId, 完成数&gt;，
     * 省掉手写 stream 收集（而且它内部做了空集合保护）。
     *
     * <p>⚠️ 时间上界是「左闭右开」：begin &lt;= finish_time &lt; end。
     * 别用 BETWEEN，它是闭区间，会把下周一 00:00:00 那条记录也算进本周。
     *
     * @param userId 学员id
     * @param begin  本周一 00:00:00（含）
     * @param end    下周一 00:00:00（不含）
     * @return 每个 lessonId 对应本周完成的小节数。本周没有完成记录的课不会出现在结果里，
     *         所以调用方取值时要 getOrDefault(lessonId, 0)
     */
    List<IdAndNumDTO> countLearnedSections(
            @Param("userId") Long userId,
            @Param("begin") LocalDateTime begin,
            @Param("end") LocalDateTime end);

    /**
     * 统计某用户本周「计划内课程」已完成的小节数。
     *
     * <p>为什么要 JOIN learning_lesson：learning_record 表里只有 lesson_id，
     * 光看记录本身不知道这门课有没有制定计划。而「本周计划」的分母（queryTotalPlan）
     * 是限定在 {@code plan_status = 1} 的课上的 —— 分子如果不限定课程范围，
     * 把「没制定计划的课」的完成数也收进来，两个数就不同源，
     * 会出现「完成数大于计划数」这种解释不通的数字。
     *
     * <p>时间范围和 countLearnedSections 一致：左闭右开 [begin, end)。
     *
     * <p>⚠️ 不需要再判断课表 status：分子只统计「本周内完成的记录」，
     * 时间条件本身已经保证这门课本周是活跃的，和分母的 EXISTS 判断天然对齐。
     *
     * @param userId 学员id
     * @param begin  本周一 00:00:00（含）
     * @param end    下周一 00:00:00（不含）
     * @return 本周完成的小节数；一节没学返回 0
     */
    Integer countWeekFinished(@Param("userId") Long userId,
                              @Param("begin") LocalDateTime begin,
                              @Param("end") LocalDateTime end);
}
