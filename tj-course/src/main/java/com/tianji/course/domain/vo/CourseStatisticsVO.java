package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 课程数据统计
 * @author wusongsong
 * @since 2022/7/10 15:36
 * @version 1.0.0
 **/
@Data
@Schema(description = "课程统计数据")
public class CourseStatisticsVO {
    @Schema(description = "课程总数量")
    private Integer totalNum;
    @Schema(description = "上架课程数量")
    private Integer onSaleNum;
    @Schema(description = "下架课程数量")
    private Integer offShelfNum;
    @Schema(description = "待上架课程数量")
    private Integer noSaleNum;
    @Schema(description = "完结课程数量")
    private Integer finishedNum;
    @Schema(description = "录播课程数量")
    private Integer recordNum;
    @Schema(description = "直播课程数")
    private Integer liveNum;

}
