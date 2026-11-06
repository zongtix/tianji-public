package com.tianji.learning.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 在现有消息表中重试的本地榜单同步任务，不发送到 RabbitMQ。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointsBoardSyncMessage {
    public static final String ROUTING_KEY = "points.board.sync";

    private Long userId;
    private String month;
}
