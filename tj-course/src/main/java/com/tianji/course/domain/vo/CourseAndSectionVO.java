package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
@Schema(description = "课程和目录及学习进度信息")
public class CourseAndSectionVO {
    @Schema(description = "id")
    private Long id;
    @Schema(description = "课程名称")
    private String name;
    @Schema(description = "课程封面")
    private String coverUrl;
    @Schema(description = "课程章节数量")
    private Integer sections;
    @Schema(description = "教师头像")
    private String teacherIcon;
    @Schema(description = "教师名称")
    private String teacherName;
    @Schema(description = "id")
    private Long lessonId;
    @Schema(description = "正在学习的小节id")
    private Long latestSectionId;
    private List<ChapterVO> chapters;
}