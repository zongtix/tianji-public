package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 题目数据
 * @author wusongsong
 * @since 2022/7/11 20:24
 * @version 1.0.0
 **/
@Data
@Schema(description = "题目分页数据")
public class SubjectVO {
    @Schema(description = "题目id")
    private Long id;
    @Schema(description = "名称")
    private String name;
    @Schema(description = "所属分类,每一个分类三级分类中间使用/分开")
    private List<String> cates;
    @Schema(description = "题目类型")
    private Integer subjectType;
    @Schema(description = "题目类型描述")
    private String subjectTypeDesc;
    @Schema(description = "题目难易度描述")
    private String difficultDesc;
    @Schema(description = "难易度,1：简单，2：中等，3：困难")
    private String difficulty;
    @Schema(description = "分值")
    private Integer score;
    @Schema(description = "使用次数")
    private Integer useTimes;
    @Schema(description = "作答次数")
    private Integer answerTimes;
    @Schema(description = "更新人")
    private String updaterName;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "选项")
    private List<String> options;
    @Schema(description = "答案,判断题，数组第一个如果是1，代表正确，其他代表错误")
    private List<Integer> answers;
    @Schema(description = "解析")
    private String analysis;
    @Schema(description = "正确率，百分号精确到小数点后一位")
    private String accuRate;
}
