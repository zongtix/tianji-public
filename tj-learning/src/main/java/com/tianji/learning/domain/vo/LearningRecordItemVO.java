package com.tianji.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
@Schema(description = "小节学习记录")
public class LearningRecordItemVO {

    @Schema(description = "小节id")
    private Long sectionId;

    @Schema(description = "视频播放进度，单位秒")
    private Integer moment;

    @Schema(description = "是否完结")
    private Boolean finished;
}
