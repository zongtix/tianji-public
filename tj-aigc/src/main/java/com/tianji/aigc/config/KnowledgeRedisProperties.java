package com.tianji.aigc.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("tj.ai.knowledge.redis")
public class KnowledgeRedisProperties {
    @NotBlank
    private String host = "127.0.0.1";
    @Min(1) @Max(65535)
    private int port = 36379;
    private String username = "default";
    @NotBlank
    @ToString.Exclude
    private String password;
    @Pattern(regexp = "tj_aigc_[a-zA-Z0-9_]+")
    private String indexName = "tj_aigc_knowledge_v4_1024";
    @Pattern(regexp = "tj:aigc:knowledge:[a-zA-Z0-9_:]+:")
    private String prefix = "tj:aigc:knowledge:v4:1024:";
}
