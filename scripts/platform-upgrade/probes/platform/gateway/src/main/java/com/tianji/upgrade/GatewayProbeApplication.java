package com.tianji.upgrade;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayProbeApplication {
    @Bean RouteLocator probeRoutes(RouteLocatorBuilder builder) {
        return builder.routes().route("isolated-probe", route -> route.path("/probe/**")
            .filters(filters -> filters.stripPrefix(1)).uri("http://127.0.0.1:19198")).build();
    }
}
