package com.tianji.common.autoconfigure;

import com.tianji.common.annotations.NoWrapper;
import com.tianji.common.autoconfigure.mvc.JsonConfig;
import com.tianji.common.autoconfigure.mvc.MvcConfig;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.autoconfigure.mvc.converter.WrapperResponseMessageConverter;
import com.tianji.common.autoconfigure.swagger.Knife4jConfiguration;
import com.tianji.common.domain.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Boot3MvcContractTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, ValidationAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(JsonConfig.class, MvcConfig.class, Knife4jConfiguration.class, Endpoints.class)
            .withPropertyValues("tj.swagger.enable=true", "tj.swagger.package-path=com.tianji.common",
                    "tj.swagger.enable-response-wrap=true", "tj.swagger.title=Contract");

    @Test
    void boot3DiscoversCommonConfigurationsAndCreatesMvcBeans() {
        Set<String> candidates = new LinkedHashSet<>();
        ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).forEach(candidates::add);
        for (String name : Set.of("mq.MqConfig", "mvc.JsonConfig", "mvc.MvcConfig", "mvc.ParamCheckerConfig",
                "mybatis.MybatisConfig", "redisson.RedissonConfig", "swagger.Knife4jConfiguration", "xxljob.XxlJobConfig")) {
            assertThat(candidates).contains("com.tianji.common.autoconfigure." + name);
        }
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CommonExceptionAdvice.class)
                    .hasSingleBean(WrapperResponseMessageConverter.class)
                    .hasSingleBean(org.springdoc.core.models.GroupedOpenApi.class)
                    .hasSingleBean(org.springdoc.core.customizers.GlobalOperationCustomizer.class);
        });
    }

    @Test
    void keepsLongDateTimeAndGatewayWrappingContracts() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/contract")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("9007199254740993"))
                    .andExpect(jsonPath("$.time").value("2026-10-12 12:34:56"))
                    .andExpect(jsonPath("$.code").doesNotExist());
            mvc.perform(get("/contract").header("x-request-from", "gateway"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("9007199254740993"));
            mvc.perform(get("/wrapped").header("x-request-from", "gateway"))
                    .andExpect(jsonPath("$.data").value("value"))
                    .andExpect(jsonPath("$.data.code").doesNotExist());
        });
    }

    @Test
    void noWrapperOnClassAndMethodSkipsGatewayWrapping() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/unwrapped/class").header("x-request-from", "gateway"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("raw"))
                    .andExpect(jsonPath("$.code").doesNotExist());
            mvc.perform(get("/unwrapped/method").header("x-request-from", "gateway"))
                    .andExpect(status().isOk()).andExpect(content().string("raw"));
            mvc.perform(get("/contract").header("x-request-from", "gateway"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("9007199254740993"));
        });
    }

    @Test
    void jakartaValidationRetainsBusinessErrorEnvelope() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(post("/contract").contentType(MediaType.APPLICATION_JSON)
                            .header("x-request-from", "gateway").content("{\"count\":0}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        });
    }

    @Test
    void openApiBytesRemainJsonThroughGateway() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/v3/api-docs/default").header("x-request-from", "gateway"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.openapi").value("3.0.1"))
                    .andExpect(jsonPath("$.code").doesNotExist());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {
        @Bean ContractController contractController() { return new ContractController(); }
        @Bean UnwrappedController unwrappedController() { return new UnwrappedController(); }
    }

    @RestController
    @NoWrapper
    static class UnwrappedController {
        @GetMapping("/unwrapped/class")
        public Map<String, String> get() { return Map.of("value", "raw"); }
    }

    @RestController
    static class ContractController {
        @GetMapping("/contract")
        public Map<String, Object> get() {
            return Map.of("id", Long.valueOf("9007199254740993"),
                    "time", LocalDateTime.of(2026, 10, 12, 12, 34, 56));
        }
        @GetMapping("/wrapped") public R<String> wrapped() { return R.ok("value"); }
        @NoWrapper
        @GetMapping(value = "/unwrapped/method", produces = MediaType.TEXT_PLAIN_VALUE)
        public String unwrapped() { return "raw"; }
        @PostMapping("/contract") public Input validate(@Valid @RequestBody Input input) { return input; }
        @GetMapping(value = "/v3/api-docs/default", produces = MediaType.APPLICATION_JSON_VALUE)
        public byte[] docs() { return "{\"openapi\":\"3.0.1\"}".getBytes(StandardCharsets.UTF_8); }
    }

    record Input(@Min(1) int count) { }
}
