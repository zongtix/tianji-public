package com.tianji.authsdk.gateway.config;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.sun.net.httpserver.HttpServer;
import com.tianji.authsdk.gateway.util.AuthUtil;
import com.tianji.authsdk.gateway.util.JwtSignerHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAuthAutoConfigurationTest {
    @Test
    void registeredConfigurationCreatesAuthBeansAndLoadsJwkThroughDiscovery() throws Exception {
        assertThat(StreamSupport.stream(ImportCandidates.load(AutoConfiguration.class,
                        getClass().getClassLoader()).spliterator(), false).toList())
                .contains(AuthAutoConfiguration.class.getName());
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        byte[] publicKey = Base64.getEncoder().encode(keys.getPublic().getEncoded());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.sendResponseHeaders(200, publicKey.length);
            try (var output = exchange.getResponseBody()) { output.write(publicKey); }
        });
        server.start();
        try {
            DiscoveryClient discovery = mock(DiscoveryClient.class);
            when(discovery.getInstances("auth-service")).thenReturn(List.of(new DefaultServiceInstance(
                    "contract-auth", "auth-service", "127.0.0.1", server.getAddress().getPort(), false)));
            new ApplicationContextRunner().withUserConfiguration(AuthAutoConfiguration.class)
                    .withBean(DiscoveryClient.class, () -> discovery)
                    .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(AuthUtil.class).hasSingleBean(JwtSignerHolder.class);
                        JwtSignerHolder holder = context.getBean(JwtSignerHolder.class);
                        for (int attempt = 0; holder.getJwtSigner() == null && attempt < 250; attempt++) {
                            Thread.sleep(20);
                        }
                        assertThat(holder.getJwtSigner()).isNotNull();
                        String token = JWT.create().setPayload("user", Map.of("userId", 101L, "roleId", 1L))
                                .setSigner(JWTSignerUtil.createSigner("rs256", keys.getPrivate())).sign();
                        var result = context.getBean(AuthUtil.class).parseToken(token);
                        assertThat(result.success()).isTrue();
                        assertThat(result.getData().getUserId()).isEqualTo(101L);
                    });
        } finally {
            server.stop(0);
        }
    }
}
