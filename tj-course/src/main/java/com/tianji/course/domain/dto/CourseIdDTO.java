package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.course.constants.CourseErrorInfo;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * @author wusongsong
 * @since 2022/7/20 16:50
 * @version 1.0.0
 **/
@Schema(description = "课程id")
@Data
public class CourseIdDTO {
    @Schema(description = "课程id")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_OPERATE_ID_NULL)
    private Long id;
}
