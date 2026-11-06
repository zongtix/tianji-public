package com.tianji.learning.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.reply")
public class BotReplyProperties {
    /** 执行建表脚本、确认机器人账号后开启。 */
    private boolean enabled;
    private Long userId = 9999L;
    private String name = "天机小助理";
    private int cooldownSeconds = 60;
    private int userDailyLimit = 20;
    private int globalDailyLimit = 200;
}
