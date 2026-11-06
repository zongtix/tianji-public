package com.tianji.aigc.agent;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.tools.CourseMaterialTools;
import com.tianji.aigc.tools.CourseTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Component
public class ConsultAgent extends AbstractAgent {
    private static final Set<String> ALLOWED_TOOLS =
            Set.of("searchCourses", "queryCourseById", "searchCourseMaterials");
    private final ToolCallback[] callbacks;

    /** 测试与无讲义 Bean 场景：不注入 CourseMaterialTools。 */
    public ConsultAgent(ChatClient client, SystemPromptConfig prompts, CourseTools courseTools) {
        this(client, prompts, courseTools, null);
    }

    @Autowired
    public ConsultAgent(ChatClient client, SystemPromptConfig prompts, CourseTools courseTools,
                        @Nullable ObjectProvider<CourseMaterialTools> materialTools) {
        super(client, prompts);
        List<ToolCallback> merged = new ArrayList<>(Arrays.asList(ToolCallbacks.from(courseTools)));
        CourseMaterialTools materials = materialTools == null ? null : materialTools.getIfAvailable();
        if (materials != null) {
            merged.addAll(Arrays.asList(ToolCallbacks.from(materials)));
        }
        callbacks = merged.stream()
                .filter(tool -> ALLOWED_TOOLS.contains(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    @Override
    public AgentTypeEnum getAgentType() {
        return AgentTypeEnum.CONSULT;
    }

    @Override
    protected ToolCallback[] tools(AgentContext context) {
        return callbacks;
    }
}
