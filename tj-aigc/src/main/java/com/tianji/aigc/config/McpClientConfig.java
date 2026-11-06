package com.tianji.aigc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.mcp.CourseMcpClient;
import com.tianji.aigc.tools.CourseDetailReader;
import com.tianji.api.client.course.CourseClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpClientConfig {
    @Bean
    public CourseDetailReader courseDetailReader(CourseClient local, McpProperties settings, ObjectMapper json,
                                                 ObjectProvider<ObservationRegistry> observations) {
        if (!settings.isEnabled() || settings.getCourseDetailSource() == McpProperties.Source.LOCAL) {
            return (id, scope) -> local.getCourseBaseInfoById(id);
        }
        Assert.hasText(settings.getCourseServerUrl(), "MCP course server URL is required");
        URI uri = URI.create(settings.getCourseServerUrl());
        Assert.isTrue(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                && uri.getFragment() == null && uri.getPath() != null && !uri.getPath().isBlank(), "Invalid MCP server URL");
        Assert.isTrue("https".equals(uri.getScheme()) || "127.0.0.1".equals(uri.getHost())
                || "localhost".equals(uri.getHost()) || "[::1]".equals(uri.getHost()), "Remote MCP requires HTTPS");
        Assert.hasText(settings.getToken(), "MCP service token is required");
        Assert.isTrue(!settings.getToken().contains("\r") && !settings.getToken().contains("\n"), "Invalid MCP service token");
        Assert.isTrue(!settings.getRequestTimeout().isNegative() && !settings.getRequestTimeout().isZero()
                && !settings.getRoundBudget().isNegative() && !settings.getRoundBudget().isZero()
                && settings.getMaxConcurrentCalls() > 0, "MCP limits must be positive");
        return new CourseMcpClient(settings, json, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}
