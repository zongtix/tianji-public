package com.tianji.aigc.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 默认使用向量与关键词混合检索；A 保留为纯向量检索回退模式。 */
@Data
@Validated
@ConfigurationProperties("tj.ai.knowledge.rag")
public class KnowledgeRagProperties {
    public enum Mode { A, APRIME, B, C, D, B_CONTEXT }
    @NotNull
    private Mode mode = Mode.B;
    private boolean inheritAprime;
    private String configVersion = "rag-v1";
    private String experimentId = "none";
    private String codeVersion = "unreported";
    private String promptVersion = "knowledge-v1";
    private String dataVersion = "unreported";
    @NotNull
    @DurationMin(nanos = 1, message = "timeout must be positive")
    private Duration preparationTimeout = Duration.ofSeconds(25);
    @NotNull
    @Valid
    private Retrieval retrieval = new Retrieval();
    @NotNull
    @Valid
    private Rerank rerank = new Rerank();
    @NotNull
    @Valid
    private Context context = new Context();

    @Data
    public static class Retrieval {
        @Positive
        private int tunedTopK = 6;
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double tunedThreshold = 0.45;
        @Positive
        private int keywordTopK = 10;
        @Positive
        private int rankConstant = 60;
        @Positive
        private int fusionLimit = 12;
        @Positive
        private int contextTopK = 3;
    }

    @Data
    public static class Rerank {
        // 可使用独立凭据；复用现有服务凭据前需验证对应排序模型权限。
        private boolean enabled;
        private String endpoint = "";
        private String apiKey = "";
        private String model = "";
        @NotNull
        @DurationMin(nanos = 1, message = "timeout must be positive")
        private Duration timeout = Duration.ofSeconds(5);
        @Positive
        private int inputLimit = 12;
        @Positive
        private int outputLimit = 6;
        @Positive
        private int maxItemTokens = 4000;
        @Positive
        private int maxRequestTokens = 30000;

        @Override
        public String toString() { return "Rerank[credentials redacted]"; }
    }

    @Data
    public static class Context {
        // 估算预算，不是供应商精确计费；需按实际聊天模型核定后调整。
        @Positive
        private int windowTokens = 16384;
        @PositiveOrZero
        private int outputReserve = 2048;
        @PositiveOrZero
        private int safetyMargin = 512;

        @AssertTrue(message = "context window must exceed output reserve plus safety margin")
        public boolean isBudgetAvailable() {
            return (long) outputReserve + safetyMargin < windowTokens;
        }
    }
}
