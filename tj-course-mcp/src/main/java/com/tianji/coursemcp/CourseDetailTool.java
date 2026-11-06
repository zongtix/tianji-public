package com.tianji.coursemcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.mcp.CourseDetailContract;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** One public-data lookup, no recommendations, user state, model or memory. */
public final class CourseDetailTool {
    private final CourseClient courses;
    private final CourseMcpProperties settings;
    private final ObjectMapper json;
    private final Semaphore permits;

    public CourseDetailTool(CourseClient courses, CourseMcpProperties settings, ObjectMapper json) {
        this.courses = courses; this.settings = settings; this.json = json;
        this.permits = new Semaphore(settings.getMaxConcurrentCalls());
    }

    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        long id;
        try {
            if (arguments == null || arguments.size() != 1 || !(arguments.get("courseId") instanceof String value)) {
                return error("INVALID_ARGUMENT");
            }
            id = CourseDetailContract.parseId(value);
        } catch (IllegalArgumentException error) {
            return error("INVALID_ARGUMENT");
        }
        if (!settings.getAllowedCourseIds().contains(id)) return error("FORBIDDEN");
        if (!permits.tryAcquire()) return error("BUSY");
        try {
            // This Feign method has no fallback. An exception/null is never NOT_FOUND.
            var found = courses.getBaseInfoBatch(List.of(id));
            if (found == null) return error("UNAVAILABLE");
            CourseDetailContract.Result result;
            if (found.isEmpty()) {
                result = CourseDetailContract.Result.notFound();
            } else {
                if (found.size() != 1 || found.get(0) == null || !Long.valueOf(id).equals(found.get(0).getId())) {
                    return error("PROTOCOL");
                }
                var dto = found.get(0);
                if (dto.getCatalogue() != null && dto.getCatalogue().size() > CourseDetailContract.MAX_CATALOGUE_ITEMS) {
                    return error("TOO_LARGE");
                }
                result = CourseDetailContract.Result.found(dto);
            }
            String text = json.writeValueAsString(result);
            if (text.getBytes(StandardCharsets.UTF_8).length > CourseDetailContract.MAX_BYTES) return error("TOO_LARGE");
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
        } catch (Exception failure) {
            // Do not expose internal URLs, credentials or upstream exception messages.
            return error("UNAVAILABLE");
        } finally {
            permits.release();
        }
    }

    private McpSchema.CallToolResult error(String code) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("{\"code\":\"" + code + "\"}")), true);
    }
}
