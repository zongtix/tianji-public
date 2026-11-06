package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

/**
 * @ClassName SubjectPageParamDTO
 * @Author wusongsong
 * @Date 2022/7/11 20:07
 * @Version
 **/
@Data
@Schema(description = "题目分页参数")
public class SubjectPageParamDTO {
    @Schema(description = "一级课程分类")
    private Long firstCateId;
    @Schema(description = "二级课程分类")
    private Long secondCateId;
    @Schema(description = "三级课程分类id列表")
    private List<Long> thirdCateIds;
    @Schema(description = "难易度,1：简单，2：中等，3：困难")
    private Integer difficulty;
    @Schema(description = "名称")
    private String name;
    @Schema(description = "题目类型，1：单选题，2：多选题，3：不定向选择题，4：判断题，多选中间逗号隔开,不传表示全选")
    private String subjectTypes;
    @Schema(description = "是否全选当前用户，默认搜索所有")
    private Boolean own;
}
