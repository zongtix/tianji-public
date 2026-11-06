package com.tianji.learning.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 问答积分消息：新增一条回答产生的一次积分。
 * <p>
 * replyId 是幂等键：同一条回答只加一次分，重复投递的消息会被唯一索引挡掉。
 */
@Data
@AllArgsConstructor(staticName = "of")
@NoArgsConstructor
public class ReplyPointsMessage {
    private Long userId;
    private Long replyId;
}
