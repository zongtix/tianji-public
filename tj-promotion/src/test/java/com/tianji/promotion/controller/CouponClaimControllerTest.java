package com.tianji.promotion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.authsdk.resource.interceptors.LoginAuthInterceptor;
import com.tianji.authsdk.resource.interceptors.UserInfoInterceptor;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.promotion.mapper.CouponClaimRequestMapper;
import com.tianji.promotion.service.claim.CouponClaimService;
import com.tianji.promotion.utils.CodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CouponClaimControllerTest {
    private MockMvc mvc;
    private CouponClaimRequestMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(CouponClaimRequestMapper.class);
        mvc = MockMvcBuilders.standaloneSetup(new CouponClaimController(new CouponClaimService(mapper)))
                .addInterceptors(new UserInfoInterceptor(), new LoginAuthInterceptor())
                .setControllerAdvice(new CommonExceptionAdvice())
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter()).build();
    }

    @Test
    void unauthenticatedRequestCannotCreateClaim() throws Exception {
        mvc.perform(post("/user-coupons/1/receive-async").header("Idempotency-Key", "request-001"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper);
    }

    @Test
    void missingIdempotencyHeaderIsBusinessBadRequestInsteadOfInternalError() throws Exception {
        mvc.perform(post("/user-coupons/1/receive-async").header("user-info", "2"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/user-coupons/exchange-async").header("user-info", "2")
                        .contentType("application/json").content(exchangeBody(CodeUtil.generateCode(1, 1))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test
    void acceptedResponseExplicitlyReturnsPendingAndRequestId() throws Exception {
        mvc.perform(post("/user-coupons/1/receive-async").header("user-info", "2")
                        .header("Idempotency-Key", "request-001").param("userId", "129"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.userCouponId").doesNotExist());
    }

    @Test
    void invalidExchangeBodyDoesNotCreateClaim() throws Exception {
        mvc.perform(post("/user-coupons/exchange-async").header("user-info", "2")
                        .header("Idempotency-Key", "request-001").contentType("application/json")
                        .content(exchangeBody("invalid")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test
    void unknownRequestReturnsBadRequestAndQueryRequiresLogin() throws Exception {
        mvc.perform(get("/user-coupons/requests/unknown").header("user-info", "2"))
                .andExpect(status().isBadRequest()).andExpect(content().string("领取请求不存在"));
        mvc.perform(get("/user-coupons/requests/unknown")).andExpect(status().isUnauthorized());
    }

    @Test
    void validExchangeRequestIsAcceptedWithoutExposingCode() throws Exception {
        mvc.perform(post("/user-coupons/exchange-async").header("user-info", "2")
                        .header("Idempotency-Key", "request-001").contentType("application/json")
                        .content(exchangeBody(CodeUtil.generateCode(1, 1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.exchangeCode").doesNotExist());
    }
    private String exchangeBody(String code) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("code", code));
    }
}
