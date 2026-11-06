package com.tianji.promotion.controller;

import com.tianji.authsdk.resource.interceptors.LoginAuthInterceptor;
import com.tianji.authsdk.resource.interceptors.UserInfoInterceptor;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.dto.CouponRecommendationDTO;
import com.tianji.promotion.domain.vo.CouponRecommendationVO;
import com.tianji.promotion.domain.vo.CouponUsageVO;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.service.ICouponRecommendationService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CouponRecommendationControllerTest {

    private ICouponRecommendationService service;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(ICouponRecommendationService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new CouponRecommendationController(service))
                .setValidator(validator)
                .addInterceptors(new UserInfoInterceptor(), new LoginAuthInterceptor())
                .setControllerAdvice(new CommonExceptionAdvice())
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void routeReturnsPlanAndUsesAuthenticatedUser() throws Exception {
        when(service.recommend(any())).thenAnswer(invocation -> {
            assertEquals(2L, UserContext.getUser());
            return result();
        });

        mvc.perform(post("/coupon-recommendations").header("user-info", "2")
                        .contentType("application/json")
                        .content("{\"courseIds\":[101,102]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalAmount").value(30000))
                .andExpect(jsonPath("$.totalDiscount").value(5000))
                .andExpect(jsonPath("$.payableAmount").value(25000))
                .andExpect(jsonPath("$.userCouponIds[0]").value(11))
                .andExpect(jsonPath("$.userCouponIds[1]").value(12))
                .andExpect(jsonPath("$.couponIds[0]").value(1))
                .andExpect(jsonPath("$.usages[0].discountType").value(2))
                .andExpect(jsonPath("$.usages[0].discountAmount").value(2000))
                .andExpect(jsonPath("$.usages[0].ruleDescription").value("折扣；本次抵扣20.00元"));

        ArgumentCaptor<CouponRecommendationDTO> captor = ArgumentCaptor.forClass(CouponRecommendationDTO.class);
        verify(service).recommend(captor.capture());
        assertEquals(List.of(101L, 102L), captor.getValue().getCourseIds());
    }

    @Test
    void unauthenticatedRequestCannotReachService() throws Exception {
        mvc.perform(post("/coupon-recommendations").contentType("application/json")
                        .content("{\"courseIds\":[101]}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void invalidBodiesCannotReachService() throws Exception {
        for (String body : new String[]{"{}", "{\"courseIds\":null}", "{\"courseIds\":[]}",
                "{\"courseIds\":[null]}", "{\"courseIds\":[1,2,3,4,5,6,7,8,9,10,11]}"}) {
            mvc.perform(post("/coupon-recommendations").header("user-info", "2")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void businessErrorReturnsBadRequest() throws Exception {
        doThrow(new BadRequestException("课程不存在：999")).when(service).recommend(any());
        mvc.perform(post("/coupon-recommendations").header("user-info", "2")
                        .contentType("application/json").content("{\"courseIds\":[999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("课程不存在：999"));
    }

    private CouponRecommendationVO result() {
        CouponUsageVO usage = new CouponUsageVO();
        usage.setUserCouponId(11L);
        usage.setCouponId(1L);
        usage.setDiscountType(DiscountType.RATE_DISCOUNT);
        usage.setDiscountAmount(2000);
        usage.setRuleDescription("折扣；本次抵扣20.00元");

        CouponRecommendationVO result = new CouponRecommendationVO();
        result.setOriginalAmount(30000);
        result.setTotalDiscount(5000);
        result.setPayableAmount(25000);
        result.setUserCouponIds(List.of(11L, 12L));
        result.setCouponIds(List.of(1L, 2L));
        result.setUsages(List.of(usage));
        return result;
    }
}
