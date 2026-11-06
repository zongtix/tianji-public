package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.course.constants.CourseErrorInfo;
import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 课程分类新增模型
 *
 * @author wusongsong
 * @since 2022/7/10 12:10
 * @version 1.0.0
 **/
@Data
@Schema(description = "课程分类新增模型")
public class CategoryAddDTO {
    @Schema(description = "父分类id, 如果是新增一级分类parentId传0")
    private Long parentId;

    @Schema(description = "名称",requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = CourseErrorInfo.Msg.CATEGORY_ADD_NAME_NOT_NULL)
    @Size(max = 15, message = CourseErrorInfo.Msg.CATEGORY_ADD_NAME_SIZE)
    private String name;

    @Schema(description = "分类序号",requiredMode = Schema.RequiredMode.REQUIRED)
    @Max(value = 99, message = CourseErrorInfo.Msg.CATEGORY_ADD_INDEX_MAX_MIN)
    @Min(value = 1, message = CourseErrorInfo.Msg.CATEGORY_ADD_INDEX_MAX_MIN)
    @NotNull(message = CourseErrorInfo.Msg.CATEGORY_ADD_INDEX_NOT_NULL)
    private Integer index;

}
