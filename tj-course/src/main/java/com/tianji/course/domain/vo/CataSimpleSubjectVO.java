package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author wusongsong
 * @since 2022/8/15 16:04
 * @version 1.0.0
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CataSimpleSubjectVO {
    @Schema(description = "小节或练习id")
    private Long cataId;
    @Schema(description = "题目id")
    private List<SubjectInfo> subjects;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SubjectInfo{
        private Long id;
        private String name;
    }
}
