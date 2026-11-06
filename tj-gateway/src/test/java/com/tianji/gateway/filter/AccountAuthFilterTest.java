package com.tianji.gateway.filter;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.tianji.auth.common.constants.JwtConstants;
import com.tianji.authsdk.gateway.util.AuthUtil;
import com.tianji.authsdk.gateway.util.JwtSignerHolder;
import com.tianji.common.domain.dto.LoginUserDTO;
import com.tianji.gateway.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAuthFilterTest {
    @Test
    void noTokenCannotForwardForgedIdentityOnUnrestrictedPath() {
        AuthUtil auth = new AuthUtil(mock(JwtSignerHolder.class), mock(StringRedisTemplate.class));
        ServerWebExchange forwarded = forward(auth, Collections.emptySet(),
                MockServerHttpRequest.post("/rs/likes").header("user-info", "20").build());

        assertNull(forwarded.getRequest().getHeaders().getFirst("user-info"));
    }

    @Test
    void invalidTokenCannotForwardForgedIdentity() {
        AuthUtil auth = new AuthUtil(mock(JwtSignerHolder.class), mock(StringRedisTemplate.class));
        ServerWebExchange forwarded = forward(auth, Collections.emptySet(),
                MockServerHttpRequest.post("/rs/likes")
                        .header("authorization", "invalid-token").header("user-info", "20").build());

        assertNull(forwarded.getRequest().getHeaders().getFirst("user-info"));
    }

    @Test
    void validTokenOverridesForgedIdentityAndOtherUserHeadersAreNotRead() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        JwtSignerHolder holder = mock(JwtSignerHolder.class);
        when(holder.getJwtSigner()).thenReturn(JWTSignerUtil.createSigner("rs256", keys.getPublic()));
        AuthUtil auth = new AuthUtil(holder, mock(StringRedisTemplate.class));
        LoginUserDTO user = new LoginUserDTO();
        user.setUserId(10L);
        String token = JWT.create().setPayload(JwtConstants.PAYLOAD_USER_KEY, user)
                .setExpiresAt(new Date(System.currentTimeMillis() + 60000))
                .setSigner(JWTSignerUtil.createSigner("rs256", keys)).sign();
        ServerWebExchange forwarded = forward(auth, Collections.emptySet(),
                MockServerHttpRequest.post("/rs/likes").header("authorization", token)
                        .header("user-info", "20", "30")
                        .header("user-id", "20").header("userId", "20").build());

        assertEquals(Collections.singletonList("10"), forwarded.getRequest().getHeaders().get("user-info"));
    }

    @Test
    void excludedPathAlsoDropsForgedIdentity() {
        ServerWebExchange forwarded = forward(mock(AuthUtil.class), Set.of("POST:/rs/likes"),
                MockServerHttpRequest.post("/rs/likes").header("User-Info", "20").build());

        assertNull(forwarded.getRequest().getHeaders().getFirst("user-info"));
    }

    @Test
    void externalClientsCannotForgeTheExistingFeignOriginMarker() {
        AuthUtil auth = new AuthUtil(mock(JwtSignerHolder.class), mock(StringRedisTemplate.class));
        AuthProperties properties = new AuthProperties();
        properties.setExcludePath(Collections.emptySet());
        AccountAuthFilter authentication = new AccountAuthFilter(auth, properties);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        MockServerHttpRequest request = MockServerHttpRequest.get("/ls/replies/exists?ids=2")
                .header(com.tianji.common.constants.Constant.REQUEST_FROM_HEADER, "feign", "other").build();

        RequestIdRelayFilter origin = new RequestIdRelayFilter();
        org.junit.jupiter.api.Assertions.assertTrue(origin.getOrder() < authentication.getOrder());
        origin.filter(MockServerWebExchange.from(request), marked -> authentication.filter(marked, exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        })).block();

        assertEquals(Collections.singletonList("gateway"), forwarded.get().getRequest().getHeaders()
                .get(com.tianji.common.constants.Constant.REQUEST_FROM_HEADER));
    }

    private ServerWebExchange forward(AuthUtil auth, Set<String> excluded,
                                     MockServerHttpRequest request) {
        AuthProperties properties = new AuthProperties();
        properties.setExcludePath(excluded);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        new AccountAuthFilter(auth, properties).filter(MockServerWebExchange.from(request), exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        }).block();
        return forwarded.get();
    }
}
