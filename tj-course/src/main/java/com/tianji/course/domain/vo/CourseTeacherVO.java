package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 课程相关老师信息
 * @ClassName CourseTeacherVO
 * @Author wusongsong
 * @Date 2022/7/11 18:17
 * @Version
 **/
@Data
@Schema(name = "老师课程信息")
public class CourseTeacherVO {
    @Schema(description = "老师课程关系id")
    private Long id;
    @Schema(description = "老师头像")
    private String icon;
    @Schema(description = "形象照")
    private String photo;
    @Schema(description = "老师姓名")
    private String name;
    @Schema(description = "老师介绍")
    private String introduce;
    @Schema(description = "用户端是否显示")
    private Boolean isShow;
    @Schema(description = "职位")
    private String job;

}
