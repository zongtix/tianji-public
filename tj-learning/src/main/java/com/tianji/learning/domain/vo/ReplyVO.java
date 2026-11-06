package com.tianji.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * learning 返回前端的回答/评论展示模型，也是 remark 两个查询结果最终汇入的位置。
 * 正文/时间/数据库计数先由 InteractionReply 复制，再批量补用户昵称头像、目标用户名和个人 liked 状态。
 * likedTimes 优先使用 remark 的即时 Map 值，降级无值时保留数据库快照；liked 来自当前用户关系 Set。
 * PO 不含这些完整展示信息，且 likedVersion 属于内部同步字段，不需要直接暴露整个实体。
 */
@Data
@Schema(description = "互动回答信息")
public class ReplyVO {
    @Schema(description = "是否是机器人回复")
    private Boolean bot = false;
    @Schema(description = "id")
    private Long id;
    @Schema(description = "回答内容")
    private String content;
    @Schema(description = "是否匿名提问")
    private Boolean anonymity;
    @Schema(description = "是否隐藏")
    private Boolean hidden;
    @Schema(description = "评论数量")
    private Integer replyTimes;
    @Schema(description = "创建时间，也就是回答时间")
    private LocalDateTime createTime;

    @Schema(description = "当前回复者id")
    private Long userId;
    @Schema(description = "当前回复者昵称")
    private String userName;
    @Schema(description = "当前回复者头像")
    private String userIcon;
    @Schema(description = "当前回复者类型，2-学员，其它-老师")
    private Integer userType;

    @Schema(description = "是否点过赞")
    private Boolean liked;
    @Schema(description = "点赞数量")
    private Integer likedTimes;
    @Schema(description = "目标用户名字")
    private String targetUserName;
}
