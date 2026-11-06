package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
@Schema(description = "章信息")
public class ChapterVO {
    @Schema(description = "章id")
    private Long id;
    @Schema(description = "章索引")
    private Integer index;
    @Schema(description = "章名称")
    private String name;
    @Schema(description = "本章视频总时长")
    private Integer mediaDuration;

    private List<SectionVO> sections;
}
