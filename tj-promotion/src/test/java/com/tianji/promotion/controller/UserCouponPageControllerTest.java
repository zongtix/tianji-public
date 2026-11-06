package com.tianji.promotion.controller;

import com.tianji.authsdk.resource.interceptors.LoginAuthInterceptor;
import com.tianji.authsdk.resource.interceptors.UserInfoInterceptor;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.UserCouponPageVO;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.service.IUserCouponService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserCouponPageControllerTest {
    private IUserCouponService service;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(IUserCouponService.class);
        when(service.queryUserCouponPage(any())).thenReturn(PageDTO.empty(0L, 0L));
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new UserCouponController(service))
                .addInterceptors(new UserInfoInterceptor(), new LoginAuthInterceptor())
                .setControllerAdvice(new CommonExceptionAdvice()).setValidator(validator)
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter()).build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
        UserContext.removeUser();
    }

    @Test
    void missingRouteNowReturnsStandardEmptyPage() throws Exception {
        mvc.perform(get("/user-coupons/page").header("user-info", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.pages").value(0)).andExpect(jsonPath("$.list").isEmpty());
        ArgumentCaptor<UserCouponQuery> query = ArgumentCaptor.forClass(UserCouponQuery.class);
        verify(service).queryUserCouponPage(query.capture());
        assertEquals(1, query.getValue().getStatus());
        assertEquals(1, query.getValue().getPageNo());
        assertEquals(20, query.getValue().getPageSize());
    }

    @Test
    void anonymousPageRequestNeverReachesService() throws Exception {
        mvc.perform(get("/user-coupons/page")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void requestUserIdCannotReplaceAuthenticatedIdentity() throws Exception {
        when(service.queryUserCouponPage(any())).thenAnswer(invocation -> {
            assertEquals(2L, UserContext.getUser());
            return PageDTO.empty(0L, 0L);
        });
        mvc.perform(get("/user-coupons/page").header("user-info", "2").param("userId", "3"))
                .andExpect(status().isOk());
        verify(service).queryUserCouponPage(any());
    }

    @Test
    void currentFrontendQueryAndAllThreeStatusTabsAreAccepted() throws Exception {
        for (String status : new String[]{"1", "2", "3"}) {
            mvc.perform(get("/user-coupons/page").header("user-info", "2")
                    .param("status", status).param("pageNo", "1").param("pageSize", "1000"))
                    .andExpect(status().isOk());
        }
        verify(service, times(3)).queryUserCouponPage(any());
    }

    @Test
    void invalidStatusAndBlankStatusAreRejected() throws Exception {
        for (String status : new String[]{"0", "4", "-1", "abc", ""}) {
            mvc.perform(get("/user-coupons/page").header("user-info", "2").param("status", status))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void invalidPaginationCannotReachService() throws Exception {
        for (String field : new String[]{"pageNo", "pageSize"}) {
            for (String value : new String[]{"0", "-1", "abc", ""}) {
                mvc.perform(get("/user-coupons/page").header("user-info", "2").param(field, value))
                        .andExpect(status().isBadRequest());
            }
        }
        mvc.perform(get("/user-coupons/page").header("user-info", "2").param("pageSize", "1001"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void pageAndRuleFieldsAreBoundAndSerializedWithoutPrivateOrTemplateState() throws Exception {
        UserCouponPageVO coupon = new UserCouponPageVO().setId(11L).setCouponId(101L).setName("本人优惠券")
                .setSpecific(true).setDiscountType(DiscountType.RATE_DISCOUNT).setThresholdAmount(10000)
                .setDiscountValue(80).setMaxDiscountAmount(3000).setStatus(UserCouponStatus.UNUSED)
                .setTermEndTime(LocalDateTime.of(2026, 11, 1, 0, 0));
        when(service.queryUserCouponPage(any())).thenReturn(new PageDTO<>(7L, 4L, Collections.singletonList(coupon)));
        mvc.perform(get("/user-coupons/page").header("user-info", "2").param("pageNo", "2").param("pageSize", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.pages").value(4)).andExpect(jsonPath("$.list[0].id").value(11))
                .andExpect(jsonPath("$.list[0].couponId").value(101))
                .andExpect(jsonPath("$.list[0].discountType").value(2))
                .andExpect(jsonPath("$.list[0].status").value(1))
                .andExpect(jsonPath("$.list[0].termEndTime").exists())
                .andExpect(jsonPath("$.list[0].userId").doesNotExist())
                .andExpect(jsonPath("$.list[0].issueNum").doesNotExist())
                .andExpect(jsonPath("$.list[0].termDays").doesNotExist())
                .andExpect(jsonPath("$.list[0].exchangeCode").doesNotExist());
        ArgumentCaptor<UserCouponQuery> query = ArgumentCaptor.forClass(UserCouponQuery.class);
        verify(service).queryUserCouponPage(query.capture());
        assertEquals(2, query.getValue().getPageNo());
        assertEquals(2, query.getValue().getPageSize());
    }
}
