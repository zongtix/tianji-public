package com.tianji.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * learning 的 interaction_reply 持久化实体，同时保存问题回答与回答下的评论正文。
 * questionId 定位问题，列表查询以 answer_id=0 查根回答，评论的 answer_id 指向根回答；target* 表示具体回复对象。
 * likedTimes 是对象总数的冗余快照，likedVersion 是已应用消息版本；个人点赞关系保存在 remark。
 * 列表分页/排序先用此表，展示再组装 ReplyVO 并补即时数量与用户信息；异步同步窗口内排序与展示计数可能不同。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("interaction_reply")
public class InteractionReply implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 互动问题的回答id
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 互动问题问题id
     */
    private Long questionId;

    /**
     * 回复的上级回答id
     */
    private Long answerId;

    /**
     * 回答者id
     */
    private Long userId;

    /**
     * 回答内容
     */
    private String content;

    /**
     * 回复的目标用户id
     */
    private Long targetUserId;

    /**
     * 回复的目标回复id
     */
    private Long targetReplyId;

    /**
     * 评论数量
     */
    private Integer replyTimes;

    /**
     * 点赞数量
     */
    private Integer likedTimes;

    /**
     * 已应用的点赞数版本，防止 MQ 中较旧的快照覆盖新值。
     */
    private Long likedVersion;

    /**
     * 是否被隐藏，默认false
     */
    private Boolean hidden;

    /**
     * 是否匿名，默认false
     */
    private Boolean anonymity;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
