package com.tianji.aigc.knowledge;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.concurrent.Callable;

/** 只记录阶段元数据，默认不采集问题、原文或回答。 */
public class RagTelemetry {
    private final ObservationRegistry registry;

    public RagTelemetry(ObservationRegistry registry) { this.registry = registry; }

    public <T> T stage(String stage, Callable<T> work) {
        Observation observation = Observation.start("rag." + stage, registry);
        try (var scope = observation.openScope()) {
            return work.call();
        } catch (RuntimeException e) {
            observation.error(new IllegalStateException(stage + ":" + failureType(e)));
            throw e;
        } catch (Exception e) {
            // 受检异常保留根因类别（如 IOException），避免降级观测一律变成 IllegalStateException。
            observation.error(new IllegalStateException(stage + ":" + failureType(e)));
            throw new StageException(stage, e);
        } finally {
            observation.stop();
        }
    }

    public void attribute(String key, Object value) {
        Observation current = registry.getCurrentObservation();
        if (current != null) current.highCardinalityKeyValue("rag." + key, String.valueOf(value));
    }

    public ObservationRegistry registry() { return registry; }

    /**
     * 降级/失败分类用：穿透本类 StageException 包装，只取异常类型名，不输出 message 或文档正文。
     */
    public static String failureType(Throwable error) {
        Throwable cursor = error;
        while (cursor instanceof StageException && cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor == null ? "Unknown" : cursor.getClass().getSimpleName();
    }

    /** 阶段受检失败的运行时包装；{@link #failureType} 会回退到 cause 的真实类别。 */
    public static final class StageException extends RuntimeException {
        public StageException(String stage, Throwable cause) {
            super("知识库阶段失败：" + stage, cause);
        }
    }
}
