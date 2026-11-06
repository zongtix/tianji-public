package com.tianji.gateway.swagger;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

class GatewaySwaggerRoutingTest {
    @Test
    void aggregatedDocsUseRoutePrefixAndGroupAndExcludeDuplicateOrLocalRoutes() {
        RouteLocator routes = () -> Flux.just(route("us", "lb://user-service"),
                route("user-alias", "lb://user-service"), route("gateway", "lb://gateway-service"),
                route("forward", "forward:/local"));
        GatewaySwaggerResourceProvider provider = new GatewaySwaggerResourceProvider(routes);
        ReflectionTestUtils.setField(provider, "gatewayName", "gateway-service");
        WebTestClient.bindToController(new SwaggerResourceController(provider)).build()
                .get().uri("/openapi-config").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.urls.length()").isEqualTo(1)
                .jsonPath("$.urls[0].name").isEqualTo("user-service")
                .jsonPath("$.urls[0].url").isEqualTo("/us/v3/api-docs/default");
    }

    @Test
    void existingDocsEntryRedirectsToNewUi() {
        WebTestClient.bindToController(new SwaggerResourceController(null)).build()
                .get().uri("/doc.html").exchange().expectStatus().isFound()
                .expectHeader().location("/swagger-ui/index.html");
    }

    private static Route route(String id, String uri) {
        return Route.async().id(id).uri(uri).predicate(exchange -> true).build();
    }
}
