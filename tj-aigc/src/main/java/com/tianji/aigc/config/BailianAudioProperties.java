package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.audio")
public class BailianAudioProperties {
    private boolean enabled = true;
    private String apiKey = "";
    private String recognitionModel = "paraformer-realtime-v2";
    private String speechModel = "cosyvoice-v3-flash";
    private String voice = "longanyang";
    private int userDailyLimit = 20;
    private int globalDailyLimit = 200;
}
