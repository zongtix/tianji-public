package com.tianji.learning.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor(staticName = "of")
@NoArgsConstructor
public class SignInMessage {
    private Long userId;
    private Integer points;

    /**
     * 签到日期(yyyy-MM-dd)，作为幂等键：同一天只加一次签到分，消息重投不会重复加分
     */
    private String bizId;
}
