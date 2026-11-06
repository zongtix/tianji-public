package com.tianji.aigc.mcp;

/** Carries only safe categories; remote messages and credentials never become SSE payloads. */
public class McpToolException extends RuntimeException {
    public enum Kind { TIMEOUT, UNAVAILABLE, PROTOCOL, FORBIDDEN, BUSY }
    private final Kind kind;

    public McpToolException(Kind kind) {
        super("课程详情查询暂不可用，请稍后重试。");
        this.kind = kind;
    }

    public Kind kind() { return kind; }
    public String code() { return "MCP_TOOL_" + kind.name(); }

    public static McpToolException find(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause()) {
            if (error instanceof McpToolException failure) return failure;
        }
        return null;
    }
}
