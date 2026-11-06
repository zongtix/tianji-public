package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 题目详情
 *
 * @author wusongsong
 * @since 2022/7/11 20:54
 * @version 1.0.0
 **/
@Data
@Schema(description = "题目详情")
public class SubjectInfoVO {
    @Schema(description = "题目id")
    private Long id;
    @Schema(description = "名称")
    private String name;
    @Schema(description = "所属题目分类")
    private List<CateSimpleInfoVO> cates;
    @Schema(description = "题目类型")
    private Integer subjectType;
    @Schema(description = "题目难易度")
    private Integer difficulty;
    @Schema(description = "分值")
    private Integer score;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
    @Schema(description = "更新人")
    private String updaterName;
    @Schema(description = "课程名称信息")
    private List<CourseSimpleInfoVO> courses;

    @Schema(description = "选项")
    private List<String> options;
    @Schema(description = "答案,判断题，数组第一个如果是1，代表正确，其他代表错误")
    private List<Integer> answers;
    @Schema(description = "解析")
    private String analysis;
    @Schema(description = "课程id列表")
    private List<Long> courseIds;
    @Schema(description = "被引用次数", example = "10")
    private Integer useTimes;
    @Schema(description = "作答次数")
    private Integer answerTimes;
    @Schema(description = "正确率")
    private Double correctRate;


}
