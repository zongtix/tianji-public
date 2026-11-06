package com.tianji.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bot_reply_task")
public class BotReplyTask {
    @TableId(type = IdType.INPUT)
    private Long sourceReplyId;
    private Long userId;
    private String status;
    private Long responseReplyId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
