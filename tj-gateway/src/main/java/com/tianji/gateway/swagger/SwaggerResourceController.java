package com.tianji.gateway.swagger;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SwaggerResourceController {

    private final GatewaySwaggerResourceProvider gatewaySwaggerResourceProvider;

    @GetMapping("/doc.html")
    public ResponseEntity<Void> swaggerUi() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/swagger-ui/index.html")).build();
    }

    @GetMapping("/openapi-config")
    public Mono<Map<String, Object>> swaggerResources() {
        return gatewaySwaggerResourceProvider.get()
                .map(urls -> Map.of("urls", urls, "validatorUrl", ""));
    }
}
