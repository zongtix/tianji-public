package com.tianji.api.dto.course;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "按平台记录报名量排序的课程，非近期热度榜")
public class CoursePopularityDTO {
    private Long id;
    @Schema(description = "平台记录的报名量；null 表示未提供，不代表零")
    private Integer sold;
}
