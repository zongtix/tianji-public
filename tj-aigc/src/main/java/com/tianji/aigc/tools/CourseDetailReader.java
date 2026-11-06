package com.tianji.aigc.tools;

import com.tianji.aigc.mcp.McpCallScope;
import com.tianji.api.dto.course.CourseBaseInfoDTO;

@FunctionalInterface
public interface CourseDetailReader {
    CourseBaseInfoDTO read(Long id, McpCallScope scope);

    /** Local readers preserve the existing lifecycle; MCP readers check before local mutations. */
    default void check(McpCallScope scope) { }
}
