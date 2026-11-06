package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.RagObservabilityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLangfuseConfigTest {
    @TempDir Path directory;

    private ApplicationContextRunner localRunner() {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RagObservabilityConfig.class)
                .withPropertyValues("spring.config.location=classpath:/application-local.yml", "spring.profiles.active=local");
    }

    private ApplicationContextRunner runner(Path file) {
        return localRunner().withPropertyValues("TJ_AI_LANGFUSE_CONFIG=" + file.toString().replace('\\', '/'));
    }

    @Test
    void defaultPathLoadsFromUserHomeWithoutExtraIdeArguments() throws Exception {
        Path file = directory.resolve(".tianji/aigc-langfuse.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "tj.ai.knowledge.observation.langfuse.public-key=pk-home-test\n");
        localRunner().withPropertyValues("user.home=" + directory.toString().replace('\\', '/')).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("langfuseSpanExporter");
            assertThat(context.getEnvironment().getProperty("tj.ai.knowledge.observation.langfuse.public-key"))
                    .isEqualTo("pk-home-test");
        });
    }

    @Test
    void localProfileLoadsPrivateConfigWithoutDependingOnWorkingDirectoryOrParentEnvironment() throws Exception {
        Path file = directory.resolve("langfuse.properties");
        Files.writeString(file, """
                tj.ai.knowledge.observation.langfuse.enabled=${TJ_AI_LANGFUSE_ENABLED:true}
                tj.ai.knowledge.observation.langfuse.endpoint=${LANGFUSE_OTLP_TRACES_ENDPOINT:http://127.0.0.1:3000/api/public/otel/v1/traces}
                tj.ai.knowledge.observation.langfuse.public-key=${LANGFUSE_PUBLIC_KEY:pk-test}
                tj.ai.knowledge.observation.langfuse.secret-key=${LANGFUSE_SECRET_KEY:sk-test}
                """);
        runner(file).run(context -> {
            assertThat(context).hasNotFailed().hasBean("langfuseSpanExporter");
            assertThat(context.getEnvironment().getProperty("tj.ai.knowledge.observation.langfuse.endpoint"))
                    .isEqualTo("http://127.0.0.1:3000/api/public/otel/v1/traces");
        });
        runner(file).withPropertyValues("TJ_AI_LANGFUSE_ENABLED=false").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean("langfuseSpanExporter"));
        runner(file).withPropertyValues("LANGFUSE_OTLP_TRACES_ENDPOINT=https://example.test/api/public/otel/v1/traces")
                .run(context -> assertThat(context.getEnvironment().getProperty("tj.ai.knowledge.observation.langfuse.endpoint"))
                        .isEqualTo("https://example.test/api/public/otel/v1/traces"));
    }

    @Test
    void absentPrivateFileKeepsExportDisabledWithoutCredentials() {
        runner(directory.resolve("missing.properties")).run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean("langfuseSpanExporter"));
    }
}
