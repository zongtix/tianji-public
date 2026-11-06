package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.course.constants.CourseErrorInfo;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 课程视频模型
 *
 * @author wusongsong
 * @since 2022/7/11 17:12
 * @version 1.0.0
 **/
@Data
@Schema(description = "课程视频模型")
public class CourseMediaDTO {
    @Schema(description = "目录id")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_MEDIA_SAVE_MEDIA_NULL)
    private Long cataId;
    @Schema(description = "媒资id")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_MEDIA_SAVE_MEDIA_NULL)
    private Long mediaId;
    @Schema(description = "是否支持试看")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_MEDIA_SAVE_MEDIA_NULL)
    private Boolean trailer;
    @Schema(description = "媒资名称")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_MEDIA_SAVE_MEDIA_NULL)
    @Length(min = 1, message = CourseErrorInfo.Msg.COURSE_MEDIA_SAVE_MEDIA_NULL)
    private String videoName;
    @Schema(description = "媒资时长，单位s")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_MEDIA_SAVE_MEDIA_NULL)
    @Min(value = 1, message = CourseErrorInfo.Msg.COURSE_MEDIA_SAVE_MEDIA_NULL)
    private Integer mediaDuration;
}
