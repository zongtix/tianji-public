package com.tianji.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_session")
public class ChatSession implements Serializable {

    /**
     * 数据id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话id
     */
    private String sessionId;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 会话标题
     */
    private String title;

    /** 0=尚未提问，1=首轮临时标题，2=自动标题或旧会话，3=手动命名。 */
    private Integer titleStatus;

    /** 创建时绑定，null 表示普通聊天。 */
    private String knowledgeBaseId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 创建人
     */
    private Long creater;

    /**
     * 更新人
     */
    private Long updater;
}
