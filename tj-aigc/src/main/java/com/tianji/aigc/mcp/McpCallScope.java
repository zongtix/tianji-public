package com.tianji.aigc.mcp;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Local request state. The MCP budget begins at the first remote operation, once per round. */
public final class McpCallScope {
    public static final String CONTEXT_KEY = "mcpCallScope";
    /**
     * High-frequency await polls local deadline this often via {@link #checkLocalDeadline()};
     * Redis renew/isCurrent ownership is sampled on this slower cadence inside {@code CourseMcpClient.await}.
     * Stage entry, result receipt, and output paths still call {@link #check()} for a live ownership probe.
     */
    static final long REMOTE_OWNERSHIP_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private final BooleanSupplier active;
    private final BooleanSupplier owner;
    /** 0 = not started; published after budget so cross-thread readers never see started!=0 with budget==0. */
    private volatile long started;
    private volatile long budget;

    public McpCallScope(BooleanSupplier active, BooleanSupplier owner) {
        this.active = active;
        this.owner = owner;
    }

    public synchronized void start(Duration duration) {
        if (started == 0) {
            long nanos = duration.toNanos();
            long begin = System.nanoTime();
            budget = nanos;
            started = begin;
        }
        check();
    }

    /** Stage / result / output boundaries: interrupt, Redis ownership, then budget. */
    public void check() {
        checkActive();
        checkLocalDeadline();
    }

    /**
     * Local wait loop only: interrupt and round budget.
     * Does not invoke {@code active}/{@code owner}, so callers must still call {@link #check()}
     * at coarser intervals and after a result returns.
     */
    public void checkLocalDeadline() {
        if (Thread.currentThread().isInterrupted()) throw new CancelledException();
        long begin = started;
        if (begin != 0 && System.nanoTime() - begin >= budget) {
            throw new McpToolException(McpToolException.Kind.TIMEOUT);
        }
    }

    /** 输出已取回的本地事实时仍校验归属，不重新对已完成的远程操作计时。 */
    public void checkActive() {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean() || !owner.getAsBoolean()) {
            throw new CancelledException();
        }
    }

    public long remainingNanos() {
        // Callers check ownership at stage/wait boundaries. Reading the clock must not
        // issue another pair of blocking Redis requests just to calculate a timeout.
        if (Thread.currentThread().isInterrupted()) throw new CancelledException();
        long begin = started;
        long remaining = budget - (System.nanoTime() - begin);
        if (remaining <= 0) {
            check(); // Cancellation/ownership loss takes precedence over the deadline.
            throw new McpToolException(McpToolException.Kind.TIMEOUT);
        }
        return remaining;
    }

    /** Distinguishes this local lifecycle signal from unrelated provider cancellations. */
    public static final class CancelledException extends CancellationException {
        public CancelledException() { super("课程查询已停止"); }
    }

    public static boolean isCancellation(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause()) {
            if (error instanceof CancelledException) return true;
        }
        return false;
    }
}
