package com.tianji.promotion.controller;

import com.tianji.authsdk.resource.interceptors.LoginAuthInterceptor;
import com.tianji.authsdk.resource.interceptors.UserInfoInterceptor;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.service.IUserCouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserCouponControllerTest {
    private IUserCouponService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(IUserCouponService.class);
        mvc = MockMvcBuilders.standaloneSetup(new UserCouponController(service))
                .addInterceptors(new UserInfoInterceptor(), new LoginAuthInterceptor())
                .setControllerAdvice(new CommonExceptionAdvice())
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter()).build();
    }

    @Test
    void receiveRouteAcceptsCouponIdWithoutRequestBody() throws Exception {
        mvc.perform(post("/user-coupons/2108766977171226629/receive").header("user-info", "2"))
                .andExpect(status().isOk());
        verify(service).receiveCoupon(2108766977171226629L);
    }

    @Test
    void unauthenticatedRequestCannotReachService() throws Exception {
        mvc.perform(post("/user-coupons/1/receive")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void userIdentityComesFromAuthenticationHeaderInsteadOfRequestParameter() throws Exception {
        doAnswer(invocation -> {
            assertEquals(2L, UserContext.getUser());
            return null;
        }).when(service).receiveCoupon(1L);
        mvc.perform(post("/user-coupons/1/receive").header("user-info", "2").param("userId", "129"))
                .andExpect(status().isOk());
        verify(service).receiveCoupon(1L);
    }

    @Test
    void quotaRejectionReturnsExistingBusinessError() throws Exception {
        doThrow(new BadRequestException("已达到该优惠券的领取上限")).when(service).receiveCoupon(1L);
        mvc.perform(post("/user-coupons/1/receive").header("user-info", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("已达到该优惠券的领取上限"));
    }

    @Test
    void exchangeRouteUsesAuthenticatedUserAndJsonCode() throws Exception {
        doAnswer(invocation -> {
            assertEquals(2L, UserContext.getUser());
            return null;
        }).when(service).exchangeCoupon("ABCDEFGH");
        mvc.perform(post("/user-coupons/exchange").header("user-info", "2")
                        .contentType("application/json").content("{\"code\":\"ABCDEFGH\",\"userId\":129}"))
                .andExpect(status().isOk());
        verify(service).exchangeCoupon("ABCDEFGH");
    }

    @Test
    void exchangeRequiresLogin() throws Exception {
        mvc.perform(post("/user-coupons/exchange").contentType("application/json")
                        .content("{\"code\":\"ABCDEFGH\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void invalidExchangeBodiesCannotReachService() throws Exception {
        for (String body : new String[]{"{}", "{\"code\":null}", "{\"code\":\"\"}",
                "{\"code\":\"   \"}", "{\"code\":\"IIIIIIII\"}", "{\"code\":\"ABC\"}",
                "{\"code\":\"ABCDEFGHJKL\"}", "{\"code\":\"abcdefgh\"}"}) {
            mvc.perform(post("/user-coupons/exchange").header("user-info", "2")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void exchangeBusinessErrorReturnsBadRequest() throws Exception {
        doThrow(new BadRequestException("兑换码已兑换")).when(service).exchangeCoupon("ABCDEFGH");
        mvc.perform(post("/user-coupons/exchange").header("user-info", "2")
                        .contentType("application/json").content("{\"code\":\"ABCDEFGH\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string("兑换码已兑换"));
    }
}
