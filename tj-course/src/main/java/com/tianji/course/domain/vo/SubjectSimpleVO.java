package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

/**
 * @author wusongsong
 * @since 2022/8/18 11:40
 * @version 1.0.0
 **/
@Data
@Schema(description = "题目简要信息")
public class SubjectSimpleVO {
    @Schema(description = "题目id")
    private Long id;

    @Schema(description = "题干")
    private String name;

    @Schema(description = "选择题的选项")
    private List<String> options;

    @Schema(description = "分值")
    private Integer score;

    @Schema(description = "问题类型，1：单选题，2：多选题，3：不定向选择题，4：判断题，5：主观题")
    private Integer subjectType;

    @Schema(description = "难易度，1：简单，2：中等，3：困难")
    private Integer difficulty;

    @Schema(description = "解析")
    private String analysis;
}
