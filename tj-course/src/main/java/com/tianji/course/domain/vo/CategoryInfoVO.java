package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author wusongsong
 * @since 2022/7/10 15:06
 * @version 1.0.0
 **/
@Data
public class CategoryInfoVO {
    @Schema(description = "课程分类id")
    private Long id;
    @Schema(description = "课程分类名称")
    private String name;
    @Schema(description = "状态：1：正常，2：禁用")
    private Integer status;
    @Schema(description = "状态描述")
    private String statusDesc;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
    @Schema(description = "分类级别，1：一级分类，2：二级分类，3：三级分类")
    private Integer categoryLevel;
    @Schema(description = "一级分类名称")
    private String firstCategoryName;
    @Schema(description = "二级分类名称")
    private String secondCategoryName;
    @Schema(description = "排序")
    private Integer index;
}
