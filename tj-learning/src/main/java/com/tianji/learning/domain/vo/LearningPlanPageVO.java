package com.tianji.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.domain.dto.PageDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "学习计划分页统计结果")
public class LearningPlanPageVO extends PageDTO<LearningPlanVO> {
    @Schema(description = "本周积分值")
    private Integer weekPoints;
    @Schema(description = "本周完成的计划数量")
    private Integer weekFinished;
    @Schema(description = "总的计划学习数量")
    private Integer weekTotalPlan;

    public LearningPlanPageVO() {
    }

    public LearningPlanPageVO pageInfo(Long total, Long pages, List<LearningPlanVO> list) {
        this.total = total;
        this.pages = pages;
        this.list = list;
        return this;
    }

    public LearningPlanPageVO pageInfo(PageDTO<LearningPlanVO> pageDTO) {
        this.total = pageDTO.getTotal();
        this.pages = pageDTO.getPages();
        this.list = pageDTO.getList();
        return this;
    }

}