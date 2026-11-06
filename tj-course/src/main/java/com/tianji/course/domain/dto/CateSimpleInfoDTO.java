package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 三级分类
 * @ClassName CateSimpleInfoVO
 * @Author wusongsong
 * @Date 2022/7/11 20:59
 * @Version
 **/
@Data
@Schema(name = "分类")
public class CateSimpleInfoDTO {
    @Schema(description = "一级分类")
    private Long firstCateId;
    @Schema(description = "二级分类id")
    private Long secondCateId;
    @Schema(description = "三级分类id")
    private Long thirdCateId;

}
