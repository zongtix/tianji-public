package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

/**
 * @author wusongsong
 * @since 2022/7/26 9:26
 * @version 1.0.0
 **/
@Data
public class CourseSimpleInfoListDTO {

    @Schema(description = "三级分类id列表")
    private List<Long> thirdCataIds;

    @Schema(description = "课程id列表")
    private List<Long> ids;
}
