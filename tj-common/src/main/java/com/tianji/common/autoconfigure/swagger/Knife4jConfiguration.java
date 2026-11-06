package com.tianji.common.autoconfigure.swagger;

import cn.hutool.core.convert.ConverterRegistry;
import com.tianji.common.domain.R;
import com.tianji.common.utils.TjTemporalConverter;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

@Configuration
@ConditionalOnClass({OpenAPI.class, GroupedOpenApi.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "tj.swagger", name = "enable", havingValue = "true")
@EnableConfigurationProperties(SwaggerConfigProperties.class)
public class Knife4jConfiguration {

    @Bean
    public OpenAPI tianjiOpenAPI(SwaggerConfigProperties properties) {
        return new OpenAPI().info(new Info()
                .title(properties.getTitle())
                .description(properties.getDescription())
                .version(properties.getVersion())
                .contact(new Contact().name(properties.getContactName())
                        .url(properties.getContactUrl()).email(properties.getContactEmail())));
    }

    @Bean
    public GroupedOpenApi defaultApi(SwaggerConfigProperties properties) {
        return GroupedOpenApi.builder().group("default")
                .packagesToScan(properties.getPackagePath()).pathsToMatch("/**").build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "tj.swagger", name = "enable-response-wrap", havingValue = "true")
    public GlobalOperationCustomizer wrappedResponseCustomizer() {
        return (operation, handler) -> {
            if (R.class.isAssignableFrom(handler.getReturnType().getParameterType()) ||
                    operation.getResponses() == null) {
                return operation;
            }
            ApiResponse response = operation.getResponses().computeIfAbsent("200",
                    key -> new ApiResponse().description("OK"));
            if (response.getContent() == null) {
                response.setContent(new Content().addMediaType(MediaType.APPLICATION_JSON_VALUE,
                        new io.swagger.v3.oas.models.media.MediaType()));
            }
            response.getContent().forEach((type, media) -> {
                if (!MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type))) {
                    return;
                }
                Schema<?> wrapped = ModelConverters.getInstance().resolveAsResolvedSchema(
                        new AnnotatedType(R.class).resolveAsRef(false)).schema;
                if (media.getSchema() != null) {
                    wrapped.addProperty("data", media.getSchema());
                }
                media.setSchema(wrapped);
            });
            return operation;
        };
    }

    {
        ConverterRegistry.getInstance().putCustom(LocalDateTime.class,
                new TjTemporalConverter(LocalDateTime.class));
    }
}
