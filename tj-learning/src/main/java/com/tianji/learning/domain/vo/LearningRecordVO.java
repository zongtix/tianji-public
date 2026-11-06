package com.tianji.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
@Schema(description = "学习记录")
public class LearningRecordVO {

    @Schema(description = "课表id")
    private Long id;

    @Schema(description = "最近学习的小节id")
    private Long latestSectionId;

    @Schema(description = "小节的学习记录")
    private List<LearningRecordItemVO> records;
}
