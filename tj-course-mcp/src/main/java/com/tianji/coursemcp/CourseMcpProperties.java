package com.tianji.coursemcp;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@Data
@ConfigurationProperties("tj.mcp.server")
public class CourseMcpProperties {
    @ToString.Exclude
    private String token;
    private Set<Long> allowedCourseIds = Set.of();
    private int maxConcurrentCalls = 4;
}
