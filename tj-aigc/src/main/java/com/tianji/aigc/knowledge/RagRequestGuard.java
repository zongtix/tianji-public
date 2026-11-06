package com.tianji.aigc.knowledge;

import com.tianji.common.exceptions.UnauthorizedException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 同一请求的有效性和准备预算；阻塞调用迟到时丢弃其结果。 */
public final class RagRequestGuard {
    private final BooleanSupplier active;
    private final long deadline;

    public RagRequestGuard(BooleanSupplier active, Duration timeout) {
        this.active = active;
        this.deadline = System.nanoTime() + timeout.toNanos();
    }

    public void check() {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) {
            throw new CancellationException("知识库请求已失效");
        }
        if (System.nanoTime() >= deadline) throw new PreparationTimeoutException();
    }

    public static boolean terminal(Throwable error) {
        for (Throwable c = error; c != null; c = c.getCause()) {
            if (c instanceof CancellationException || c instanceof PreparationTimeoutException
                    || c instanceof UnauthorizedException || c instanceof SecurityException
                    || c instanceof InterruptedException) return true;
            if (c instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException e
                    && (e.status() == 401 || e.status() == 403)) return true;
            if (c instanceof org.elasticsearch.client.ResponseException e
                    && (e.getResponse().getStatusLine().getStatusCode() == 401
                    || e.getResponse().getStatusLine().getStatusCode() == 403)) return true;
            if (c instanceof RestClientResponseException e
                    && (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)) return true;
            // Spring AI 1.1.2 的默认 error handler 把 HTTP 状态编码进该异常前缀，未保留 ResponseException。
            if (c instanceof org.springframework.ai.retry.NonTransientAiException
                    && c.getMessage() != null && (c.getMessage().startsWith("401 - ")
                    || c.getMessage().startsWith("403 - "))) return true;
        }
        return false;
    }

    public static class PreparationTimeoutException extends RuntimeException {
        public PreparationTimeoutException() { super("知识库准备超时，请稍后重试"); }
    }
}
