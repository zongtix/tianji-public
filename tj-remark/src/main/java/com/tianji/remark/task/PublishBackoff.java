package com.tianji.remark.task;

/**
 * 重试退避状态：失败按 base、2×base、4×base…延迟，最多 max，成功清零。
 * 写后任务整体发布与每条坏日志各维护实例，减少依赖故障时的密集重试。
 * 仅保存在进程内存，重启会重置等待时间；真正的待办仍保存在 Stream/PEL 或补发 Hash。
 */
public class PublishBackoff {
    private final long baseMs;
    private final long maxMs;
    private int failureCount;
    private long nextAttemptAtMs;

    public PublishBackoff(long baseMs, long maxMs) {
        this.baseMs = baseMs;
        this.maxMs = maxMs;
    }

    public boolean shouldSkip(long nowMs) {
        return nowMs < nextAttemptAtMs;
    }

    public void onFailure(long nowMs) {
        failureCount++;
        long delayMs = Math.min(baseMs, maxMs);
        // 翻倍前比较 max/2，避免乘二溢出；达到最大间隔后仍会继续尝试，并非永久停止。
        for (int i = 1; i < failureCount && delayMs < maxMs; i++) {
            delayMs = delayMs > maxMs / 2 ? maxMs : Math.min(delayMs * 2, maxMs);
        }
        nextAttemptAtMs = nowMs + delayMs;
    }

    public void onSuccess() {
        failureCount = 0;
        nextAttemptAtMs = 0;
    }

    int failureCount() {
        return failureCount;
    }

    long nextAttemptAtMs() {
        return nextAttemptAtMs;
    }
}
