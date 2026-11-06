package com.tianji.learning.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学习积分消息：学完一个小节产生的一次积分。
 * <p>
 * 带上 lessonId / sectionId 是为了让消费端知道「这条积分是哪次学习产生的」——
 * 只传 userId 的话，收到重复消息时无从判断是否已处理过，幂等就无从谈起。
 * 消费端用 sectionId 作为幂等键（同一个小节只加一次分）。
 */
@Data
@AllArgsConstructor(staticName = "of")
@NoArgsConstructor
public class LearningPointsMessage {
    private Long userId;
    private Long lessonId;
    private Long sectionId;
}
