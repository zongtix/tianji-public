package com.tianji.learning.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * learning 的回答/评论提交 DTO，用于理解 remark 点赞目标从哪里产生，不属于点赞表单。
 * 提交内容、问题及回复位置；saveReply 从登录上下文补 userId，再转换为 InteractionReply 保存。
 * 新增时 answerId 未传表示直接回答，传上级回答 ID 表示评论；targetReplyId/targetUserId 定位具体被回复对象。
 * 请求字段不包含 likedTimes、likedVersion，点赞计数由 remark 的异步消息维护。
 */
@Data
@Schema(description = "互动回答信息")
public class ReplyDTO {
    @Schema(description = "用户明确选择的 @对象 ID，第一版仅支持机器人")
    @Size(max = 1)
    private List<@NotNull Long> mentionedUserIds;

    @Schema(description = "回答内容")
    @NotNull(message = "回答内容不能为空")
    private String content;

    @Schema(description = "是否匿名提问")
    private Boolean anonymity;

    @Schema(description = "互动问题id")
    @NotNull(message = "问题id不能为空")
    private Long questionId;

    @Schema(description = "回复的上级回答id，没有可不填")
    private Long answerId;

    @Schema(description = "回复的目标回复id，没有可不填")
    private Long targetReplyId;

    @Schema(description = "回复的目标用户id，没有可不填")
    private Long targetUserId;

    @Schema(description = "标记是否是学生提交的回答，默认true")
    private Boolean isStudent = true;
}
