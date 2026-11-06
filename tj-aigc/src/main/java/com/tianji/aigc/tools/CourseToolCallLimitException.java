package com.tianji.aigc.tools;

/**
 * 课程工具回合预算耗尽；须经 ToolExecutionExceptionProcessor 重抛以打断模型工具循环。
 * 普通工具错误仍转为工具结果字符串，不能依赖默认 RuntimeException 传播。
 */
public class CourseToolCallLimitException extends RuntimeException {
    public CourseToolCallLimitException() {
        super("课程工具调用次数已达上限");
    }

    public static boolean is(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CourseToolCallLimitException) return true;
        }
        return false;
    }
}
