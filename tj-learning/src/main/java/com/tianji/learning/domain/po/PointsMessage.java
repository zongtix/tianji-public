package com.tianji.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 积分消息发送记录；与学习、回答的业务数据一起提交，发送成功后仍保留业务键。 */
@Data
@TableName("points_message")
public class PointsMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageKey;
    private String routingKey;
    private String payload;
    private Boolean sent;
    private LocalDateTime createTime;
}
