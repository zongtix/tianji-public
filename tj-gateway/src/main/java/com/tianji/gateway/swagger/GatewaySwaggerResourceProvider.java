package com.tianji.gateway.swagger;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GatewaySwaggerResourceProvider {

    private final RouteLocator routeLocator;

    @Value("${spring.application.name}")
    private String gatewayName;

    public Mono<List<Map<String, String>>> get() {
        return routeLocator.getRoutes()
                .filter(route -> route.getUri().getHost() != null)
                .filter(route -> !gatewayName.equals(route.getUri().getHost()))
                .distinct(route -> route.getUri().getHost())
                .map(route -> Map.of("name", route.getUri().getHost(),
                        "url", "/" + route.getId() + "/v3/api-docs/default"))
                .collectList();
    }
}
