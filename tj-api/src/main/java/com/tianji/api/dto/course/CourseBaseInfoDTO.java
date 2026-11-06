package com.tianji.api.dto.course;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "课程基本信息及正式课程内容")
public class CourseBaseInfoDTO {
    @Schema(description = "课程id")
    private Long id;
    @Schema(description = "课程名称")
    private String name;
    @Schema(description = "课程价格，单位：分")
    private Integer price;
    @Schema(description = "是否为免费课程")
    private Boolean free;
    @Schema(description = "课程学习有效期，单位：月")
    private Integer validDuration;
    @Schema(description = "适用人群")
    private String usePeople;
    @Schema(description = "课程详细介绍")
    private String detail;
    @Schema(description = "正式章节标题，按目录顺序排列；章与小节以 / 分隔，不代表完整教学内容")
    private List<String> catalogue;
}
