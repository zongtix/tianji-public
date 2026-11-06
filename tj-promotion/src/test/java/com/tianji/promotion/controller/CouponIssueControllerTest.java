package com.tianji.promotion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.common.autoconfigure.mvc.JsonConfig;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.promotion.domain.dto.CouponIssueDTO;
import com.tianji.promotion.service.ICouponService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CouponIssueControllerTest {
    private ICouponService service;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(ICouponService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JsonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();
        mvc = MockMvcBuilders.standaloneSetup(new CouponController(service))
                .setControllerAdvice(new CommonExceptionAdvice()).setValidator(validator)
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void immediateRequestBindsIdAndProjectDateFormat() throws Exception {
        mvc.perform(put("/coupons/123/issue").contentType(MediaType.APPLICATION_JSON)
                .content("{\"issueEndTime\":\"2099-10-31 23:59:59\",\"termDays\":30}"))
                .andExpect(status().isOk());
        ArgumentCaptor<CouponIssueDTO> captor = ArgumentCaptor.forClass(CouponIssueDTO.class);
        verify(service).issueCoupon(eq(123L), captor.capture());
        assertNull(captor.getValue().getIssueBeginTime());
        assertEquals(30, captor.getValue().getTermDays());
        assertEquals(LocalDateTime.of(2099, 10, 31, 23, 59, 59), captor.getValue().getIssueEndTime());
    }

    @Test
    void scheduledFixedTermRequestBindsAllDates() throws Exception {
        mvc.perform(put("/coupons/123/issue").contentType(MediaType.APPLICATION_JSON)
                .content("{\"issueBeginTime\":\"2099-10-10 10:00:00\",\"issueEndTime\":\"2099-10-20 10:00:00\","
                        + "\"termBeginTime\":\"2099-10-10 10:00:00\",\"termEndTime\":\"2099-11-01 10:00:00\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<CouponIssueDTO> captor = ArgumentCaptor.forClass(CouponIssueDTO.class);
        verify(service).issueCoupon(eq(123L), captor.capture());
        CouponIssueDTO dto = captor.getValue();
        assertEquals(0, dto.getTermDays());
        assertEquals(LocalDateTime.of(2099, 10, 10, 10, 0), dto.getIssueBeginTime());
        assertEquals(LocalDateTime.of(2099, 11, 1, 10, 0), dto.getTermEndTime());
    }

    @Test
    void missingEndAndInvalidDaysDoNotReachService() throws Exception {
        for (String body : new String[]{"{}", "{\"issueEndTime\":null,\"termDays\":30}",
                "{\"issueEndTime\":\"2099-10-31 23:59:59\",\"termDays\":-1}",
                "{\"issueEndTime\":\"2099-10-31 23:59:59\",\"termDays\":null}"}) {
            mvc.perform(put("/coupons/123/issue").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void businessRejectionUsesExistingExceptionAdvice() throws Exception {
        doThrow(new BadRequestException("只有草稿状态的优惠券可以发放"))
                .when(service).issueCoupon(eq(123L), any());
        mvc.perform(put("/coupons/123/issue").contentType(MediaType.APPLICATION_JSON)
                .content("{\"issueEndTime\":\"2099-10-31 23:59:59\",\"termDays\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("只有草稿状态的优惠券可以发放"));
    }
}
