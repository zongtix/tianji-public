package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author wusongsong
 * @since 2022/7/26 9:28
 * @version 1.0.0
 **/
@Data
@Schema(description = "目录简单信息")
@AllArgsConstructor
@NoArgsConstructor
public class CataSimpleInfoVO {
    @Schema(description = "目录id")
    private Long id;
    @Schema(description = "目录名称")
    private String name;
    @Schema(description = "目录序号1-1")
    private String index;
    @Schema(description = "数字序号，不包含章序号")
    private Integer cIndex;
    @Schema(description = "数字序号章序号")
    private Integer chapterIndex;
}
