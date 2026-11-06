package com.tianji.coursemcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.mcp.CourseDetailContract;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class CourseMcpConfig {
    @Bean
    feign.RequestInterceptor courseRequestOrigin() {
        return template -> template.header("x-request-from", "feign");
    }

    @Bean
    FilterRegistrationBean<McpAccessFilter> mcpAccessFilter(CourseMcpProperties settings) {
        Assert.hasText(settings.getToken(), "MCP service token is required");
        Assert.isTrue(settings.getToken().length() >= 32, "Use a service token of at least 32 characters");
        Assert.isTrue(!settings.getAllowedCourseIds().isEmpty()
                && settings.getAllowedCourseIds().stream().allMatch(id -> id != null && id > 0), "Configure approved course IDs");
        Assert.isTrue(settings.getMaxConcurrentCalls() > 0, "MCP concurrency must be positive");
        var registration = new FilterRegistrationBean<>(new McpAccessFilter(settings.getToken()));
        registration.addUrlPatterns("/mcp");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    List<McpServerFeatures.SyncToolSpecification> courseTools(CourseClient courses, CourseMcpProperties settings, ObjectMapper json) {
        var handler = new CourseDetailTool(courses, settings, json);
        var schema = new McpSchema.JsonSchema("object",
                Map.of("courseId", Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$")),
                List.of("courseId"), false, null, null);
        var tool = McpSchema.Tool.builder().name(CourseDetailContract.TOOL_NAME)
                .description("Read approved course display details. Price is integer cents; validity is months.")
                .inputSchema(schema).annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, false)).build();
        return List.of(McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handler.call(request.arguments())).build());
    }
}
