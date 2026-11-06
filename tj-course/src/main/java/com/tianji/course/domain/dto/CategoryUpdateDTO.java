package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.course.constants.CourseErrorInfo;
import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 课程分类更新类，只更新名称和排序
 * @author wusongsong
 * @since 2022/7/10 15:32
 * @version 1.0.0
 **/
@Data
@Schema(description = "分类信息更新模型")
public class CategoryUpdateDTO {
    @Schema(description = "分类id")
    @NotNull(message = CourseErrorInfo.Msg.CATEGORY_ID_NOT_NULL)
    private Long id;
    @Schema(description = "名称")
    @NotNull(message = CourseErrorInfo.Msg.CATEGORY_UPDATE_NAME_NOT_NULL)
    @Size(max = 15, message = CourseErrorInfo.Msg.CATEGORY_UPDATE_NAME_SIZE)
    private String name;
    @Schema(description = "分类序号")
    @Max(value = 99, message = CourseErrorInfo.Msg.CATEGORY_UPDATE_INDEX_MAX_MIN)
    @Min(value = 1, message = CourseErrorInfo.Msg.CATEGORY_UPDATE_INDEX_MAX_MIN)
    @NotNull(message = CourseErrorInfo.Msg.CATEGORY_UPDATE_INDEX_NOT_NULL)
    private Integer index;
}
