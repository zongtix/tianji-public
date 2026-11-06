package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 三级分类
 * @author wusongsong
 * @since 2022/7/11 20:59
 * @version 1.0.0
 **/
@Data
@Schema(description = "分类")
public class CateSimpleInfoVO {
    @Schema(description = "一级分类")
    private Long firstCateId;
    @Schema(description = "一级分类名称")
    private String firstCateName;
    @Schema(description = "二级分类id")
    private Long secondCateId;
    @Schema(description = "二级分类名称")
    private String secondCateName;
    @Schema(description = "三级分类id")
    private Long thirdCateId;
    @Schema(description = "三级分类名称")
    private String thirdCateName;

}
