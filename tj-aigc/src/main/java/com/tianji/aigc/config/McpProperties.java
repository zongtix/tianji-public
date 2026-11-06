package com.tianji.aigc.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties("tj.ai.mcp")
public class McpProperties {
    private boolean enabled;
    private Source courseDetailSource = Source.LOCAL;
    private String courseServerUrl;
    @ToString.Exclude
    private String token;
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration roundBudget = Duration.ofSeconds(20);
    // Conservative pilot limit, not a measured capacity claim.
    private int maxConcurrentCalls = 4;

    public enum Source { LOCAL, MCP }
}
