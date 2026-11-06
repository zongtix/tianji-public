package com.tianji.promotion.controller;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.service.ICouponService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CouponControllerTest {
    private ICouponService service;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(ICouponService.class);
        when(service.queryCouponPage(any())).thenReturn(PageDTO.empty(0L, 0L));
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new CouponController(service)).setValidator(validator).build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void defaultQueryUsesFirstPageAndReturnsPageDto() throws Exception {
        mvc.perform(get("/coupons/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.pages").value(0))
                .andExpect(jsonPath("$.list").isEmpty());
        ArgumentCaptor<CouponQuery> captor = ArgumentCaptor.forClass(CouponQuery.class);
        verify(service).queryCouponPage(captor.capture());
        assertEquals(1, captor.getValue().getPageNo());
        assertEquals(20, captor.getValue().getPageSize());
        assertNull(captor.getValue().getType());
        assertNull(captor.getValue().getStatus());
    }

    @Test
    void validFiltersAndPaginationAreBoundToQuery() throws Exception {
        mvc.perform(get("/coupons/page").param("name", "Java").param("type", "2")
                .param("status", "5").param("pageNo", "2").param("pageSize", "10"))
                .andExpect(status().isOk());
        ArgumentCaptor<CouponQuery> captor = ArgumentCaptor.forClass(CouponQuery.class);
        verify(service).queryCouponPage(captor.capture());
        CouponQuery query = captor.getValue();
        assertEquals("Java", query.getName());
        assertEquals(2, query.getType());
        assertEquals(5, query.getStatus());
        assertEquals(2, query.getPageNo());
        assertEquals(10, query.getPageSize());
    }

    @Test
    void invalidPageParametersReturnBadRequestWithoutCallingService() throws Exception {
        for (String field : new String[]{"pageNo", "pageSize"}) {
            for (String value : new String[]{"0", "-1", "", "abc"}) {
                mvc.perform(get("/coupons/page").param(field, value)).andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void invalidFilterValuesReturnBadRequestWithoutCallingService() throws Exception {
        for (String type : new String[]{"0", "5", "abc"}) {
            mvc.perform(get("/coupons/page").param("type", type)).andExpect(status().isBadRequest());
        }
        for (String value : new String[]{"0", "6", "abc"}) {
            mvc.perform(get("/coupons/page").param("status", value)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void issuingCouponListReturnsUserFlagsAndNumericDiscountType() throws Exception {
        CouponVO coupon = new CouponVO();
        coupon.setId(1L);
        coupon.setDiscountType(DiscountType.RATE_DISCOUNT);
        coupon.setAvailable(false);
        coupon.setReceived(true);
        when(service.queryIssuingCoupons()).thenReturn(Collections.singletonList(coupon));
        mvc.perform(get("/coupons/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].discountType").value(2))
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[0].received").value(true));
        verify(service).queryIssuingCoupons();
    }

    @Test
    void noIssuingCouponsReturnsAnEmptyArray() throws Exception {
        when(service.queryIssuingCoupons()).thenReturn(Collections.emptyList());
        mvc.perform(get("/coupons/list"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
