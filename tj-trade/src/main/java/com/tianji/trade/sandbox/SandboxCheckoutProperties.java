package com.tianji.trade.sandbox;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties("tj.trade.sandbox-checkout")
public class SandboxCheckoutProperties {
    private boolean enabled;
    private String nodeCommand = "node";
    private String serverScript;
    @ToString.Exclude private String appId;
    @ToString.Exclude private String appKey;
    @ToString.Exclude private String alipayPublicKey;
    private Set<Long> allowedCourseIds = new HashSet<>();
    private Set<Long> allowedUserIds = new HashSet<>();
}
