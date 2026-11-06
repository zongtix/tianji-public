package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * @author wusongsong
 * @since 2022/7/13 11:26
 * @version 1.0.0
 **/
@Data
@Schema(description = "课程保存结果")
@AllArgsConstructor
@NotNull
@Builder
public class CourseSaveVO {
    @Schema(description = "课程id")
    private Long id;
}
