package com.tianji.aigc.config;

import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "tj.ai.memory")
public class ChatMemoryStorageProperties {
    @Pattern(regexp = "mysql|mongodb")
    private String type = "mysql";
}
