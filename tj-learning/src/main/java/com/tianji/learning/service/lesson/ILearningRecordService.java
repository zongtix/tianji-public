package com.tianji.learning.service.lesson;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.domain.vo.LearningRecordVO;

/**
 * <p>
 * 学习记录表 服务类
 * </p>
 */
public interface ILearningRecordService extends IService<LearningRecord> {



    LearningLessonDTO queryLearningRecordByCourse(Long courseId);

    /**
     * 提交学习记录（视频播放进度 / 考试完成），并同步课表的学习进度
     *
     * @param recordDTO 学习记录表单数据
     */
    void addLearningRecord(LearningRecordFormDTO recordDTO);
}
