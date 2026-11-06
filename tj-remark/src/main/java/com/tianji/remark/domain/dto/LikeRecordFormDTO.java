package com.tianji.remark.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/**
 * 客户端提交的点赞目标状态 DTO，由 LikedRecordController 的 @Validated 触发字段校验。
 * bizType 区分 QA/NOTE，bizId 指向对应业务对象（QA 为 learning 的回答或评论 ID）。
 * liked 表示期望最终状态；不传用户身份、当前计数或版本，这些数据由服务端产生。
 * 该 DTO 不直接映射数据库行：取消点赞会删除关系，重复请求则不产生新变更。
 */
@Data
@Schema(description = "点赞记录表单实体")
public class LikeRecordFormDTO {
    @Schema(description = "点赞业务id")
    @NotNull(message = "业务id不能为空")
    @Positive(message = "业务id必须为正数")
    private Long bizId;

    @Schema(description = "点赞业务类型")
    @NotBlank(message = "业务类型不能为空")
    @Pattern(regexp = "QA|NOTE", message = "不支持的点赞业务类型")
    private String bizType;

    @Schema(description = "是否点赞，true：点赞；false：取消点赞")
    @NotNull(message = "是否点赞不能为空")
    private Boolean liked;
}
