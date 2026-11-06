package com.tianji.aigc.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.McpClientConfig;
import com.tianji.aigc.tools.CourseDetailReader;
import com.tianji.api.client.course.CourseClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpClientConfigTest {
    private final CourseClient local = mock(CourseClient.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(McpClientConfig.class)
            .withBean(CourseClient.class, () -> local).withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledOverridesRemoteSelectionAndNeedsNoUrlOrToken() {
        runner.withPropertyValues("tj.ai.mcp.course-detail-source=mcp").run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(CourseDetailReader.class).read(1L, null);
            verify(local).getCourseBaseInfoById(1L);
        });
    }

    @Test
    void enabledLocalNeedsNoRemoteConfiguration() {
        runner.withPropertyValues("tj.ai.mcp.enabled=true").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void unreachableRemoteDoesNotPreventStartupOrCreateConnectionUntilRead() {
        runner.withPropertyValues("tj.ai.mcp.enabled=true", "tj.ai.mcp.course-detail-source=mcp",
                "tj.ai.mcp.course-server-url=http://127.0.0.1:1/mcp", "tj.ai.mcp.token=test-service-token",
                "tj.ai.mcp.request-timeout=100ms").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(CourseDetailReader.class).read(1L,
                    new McpCallScope(() -> true, () -> true))).isInstanceOf(McpToolException.class);
            verifyNoInteractions(local);
        });
    }

    @Test
    void invalidSourceAndRemoteConfigurationFailClearly() {
        runner.withPropertyValues("tj.ai.mcp.course-detail-source=other").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("tj.ai.mcp.enabled=true", "tj.ai.mcp.course-detail-source=mcp")
                .run(context -> assertThat(context).hasFailed());
    }
}
