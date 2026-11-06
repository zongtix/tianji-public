package com.tianji.aigc.agent;

import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.tools.CourseTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

@Component
public class RecommendAgent extends AbstractAgent {
    private static final Set<String> ALLOWED_TOOLS = Set.of("browseCourses", "searchCourses", "searchCoursesByGoal", "queryCourseById", "recommendCourses");
    private final ToolCallback[] callbacks;

    public RecommendAgent(ChatClient client, SystemPromptConfig prompts, CourseTools courseTools) {
        super(client, prompts);
        callbacks = Arrays.stream(ToolCallbacks.from(courseTools))
                .filter(tool -> ALLOWED_TOOLS.contains(tool.getToolDefinition().name()))
                .filter(tool -> !tool.getToolDefinition().name().equals("searchCoursesByGoal") || courseTools.supportsSemanticSearch())
                .toArray(ToolCallback[]::new);
    }

    @Override
    public AgentTypeEnum getAgentType() {
        return AgentTypeEnum.RECOMMEND;
    }

    @Override
    protected ToolCallback[] tools(AgentContext context) {
        return callbacks;
    }
}
