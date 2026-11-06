package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.course.constants.CourseErrorInfo;
import lombok.Data;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 保存章节信息
 *
 * @author wusongsong
 * @since 2022/7/11 18:10
 * @version 1.0.0
 **/
@Data
@Schema(description = "小节、练习和题目关系模型")
public class CataSubjectDTO {
        @Schema(description = "题目id")
        @NotNull(message = CourseErrorInfo.Msg.COURSE_SUBJECT_SAVE_SUBJECT_IDS_NULL)
        @Size(min = 1,message = CourseErrorInfo.Msg.COURSE_SUBJECT_SAVE_SUBJECT_IDS_NULL)
        private List<Long> subjectIds;
        @Schema(description = "小节或练习id")
        @NotNull(message = CourseErrorInfo.Msg.COURSE_SUBJECT_SAVE_CATALOGUE_ID_NULL)
        private Long cataId;
}
