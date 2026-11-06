package com.tianji.authsdk.resource.config;

import com.tianji.authsdk.resource.interceptors.FeignRelayUserInterceptor;
import com.tianji.common.utils.UserContext;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResourceAutoConfigurationTest {
    @Test
    void registeredConfigurationsEnforceLoginRelayIdentityAndClearThreadContext() {
        List<String> candidates = StreamSupport.stream(ImportCandidates.load(AutoConfiguration.class,
                        getClass().getClassLoader()).spliterator(), false)
                .filter(name -> name.startsWith("com.tianji.authsdk.resource.")).toList();
        assertThat(candidates).containsExactlyInAnyOrder(ResourceInterceptorConfiguration.class.getName(),
                FeignRelayUserAutoConfiguration.class.getName());
        Class<?>[] configurations = candidates.stream().map(name -> {
            try { return Class.forName(name); }
            catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
        }).toArray(Class<?>[]::new);
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .withUserConfiguration(configurations).withUserConfiguration(Endpoints.class)
                .withPropertyValues("tj.auth.resource.enable=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ResourceInterceptorConfiguration.class)
                            .hasSingleBean(FeignRelayUserInterceptor.class);
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get("/sdk/me")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/sdk/me").header("user-info", "101"))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("101"));
                    assertThat(UserContext.getUser()).isNull();
                    mvc.perform(get("/sdk/me")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {
        @Bean IdentityController identityController(FeignRelayUserInterceptor interceptor) {
            return new IdentityController(interceptor);
        }
    }

    @RestController
    record IdentityController(FeignRelayUserInterceptor interceptor) {
        @GetMapping("/sdk/me")
        public Map<String, Object> identity() {
            RequestTemplate template = new RequestTemplate();
            interceptor.apply(template);
            return Map.of("userId", template.headers().get("user-info").iterator().next());
        }
        @GetMapping("/swagger-ui/index.html") public String docs() { return "docs"; }
    }
}
