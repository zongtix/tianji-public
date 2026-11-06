package com.tianji.promotion.service.impl;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.service.ICouponScopeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class CouponServiceImplTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void omittedOptionalFieldsUseDefaults() {
        CouponFormDTO form = validForm();
        RecordingService service = new RecordingService(null);
        assertTrue(validator.validate(form).isEmpty());
        service.saveCoupon(form);
        assertEquals(Boolean.FALSE, service.saved.getSpecific());
        assertEquals(1, service.saved.getUserLimit());
        assertEquals(0, service.saved.getThresholdAmount());
        assertEquals(0, service.saved.getMaxDiscountAmount());
    }

    @Test
    void nullSpecificIsSavedAsFalse() {
        CouponFormDTO form = validForm();
        form.setSpecific(null);
        RecordingService service = new RecordingService(null);
        service.saveCoupon(form);
        assertEquals(Boolean.FALSE, service.saved.getSpecific());
    }

    @Test
    void missingRequiredNumbersFailValidation() {
        CouponFormDTO form = validForm();
        form.setTotalNum(null);
        form.setUserLimit(null);
        form.setDiscountValue(null);
        form.setThresholdAmount(null);
        form.setMaxDiscountAmount(null);
        for (String field : Arrays.asList("totalNum", "userLimit", "discountValue",
                "thresholdAmount", "maxDiscountAmount")) {
            assertInvalidField(form, field);
        }
    }

    @Test
    void invalidNumberRangesFailValidation() {
        CouponFormDTO form = validForm();
        form.setTotalNum(0);
        form.setUserLimit(0);
        form.setDiscountValue(0);
        form.setThresholdAmount(-1);
        form.setMaxDiscountAmount(-1);
        for (String field : Arrays.asList("totalNum", "userLimit", "discountValue",
                "thresholdAmount", "maxDiscountAmount")) {
            assertInvalidField(form, field);
        }
        form.setTotalNum(5001);
        form.setUserLimit(11);
        assertInvalidField(form, "totalNum");
        assertInvalidField(form, "userLimit");
    }

    @Test
    void rateAtOrAboveOneHundredIsRejectedBeforeSaving() {
        for (int rate : new int[]{100, 120}) {
            CouponFormDTO form = validForm();
            form.setDiscountValue(rate);
            assertRejectedBeforeSaving(form);
        }
    }

    @Test
    void rateBoundariesAreAccepted() {
        for (int rate : new int[]{1, 99}) {
            CouponFormDTO form = validForm();
            form.setDiscountValue(rate);
            RecordingService service = new RecordingService(null);
            assertTrue(validator.validate(form).isEmpty());
            service.saveCoupon(form);
            assertNotNull(service.saved);
        }
    }

    @Test
    void bothFullReductionTypesRequirePositiveThresholds() {
        for (DiscountType type : Arrays.asList(DiscountType.PER_PRICE_DISCOUNT, DiscountType.PRICE_DISCOUNT)) {
            CouponFormDTO form = validForm();
            form.setDiscountType(type);
            assertRejectedBeforeSaving(form);
            form.setThresholdAmount(1000);
            form.setDiscountValue(200);
            RecordingService service = new RecordingService(null);
            service.saveCoupon(form);
            assertEquals(200, service.saved.getDiscountValue());
        }
    }

    @Test
    void noThresholdCouponRequiresZeroThreshold() {
        CouponFormDTO form = validForm();
        form.setDiscountType(DiscountType.NO_THRESHOLD);
        form.setThresholdAmount(1);
        assertRejectedBeforeSaving(form);
        form.setThresholdAmount(0);
        form.setDiscountValue(200);
        RecordingService service = new RecordingService(null);
        service.saveCoupon(form);
        assertNotNull(service.saved);
    }

    @Test
    void specificCouponWithoutScopesIsRejectedBeforeSaving() {
        CouponFormDTO form = validForm();
        form.setSpecific(true);
        assertRejectedBeforeSaving(form);
        form.setScopes(Collections.emptyList());
        assertRejectedBeforeSaving(form);
    }

    @Test
    void scopesAreLinkedToTheNewCoupon() {
        CouponFormDTO form = validForm();
        form.setSpecific(true);
        form.setScopes(Arrays.asList(10L, 20L));
        int[] scopeSaveCalls = {0};
        ICouponScopeService scopeService = (ICouponScopeService) Proxy.newProxyInstance(
                ICouponScopeService.class.getClassLoader(), new Class<?>[]{ICouponScopeService.class},
                (proxy, method, args) -> {
                    assertEquals("saveBatch", method.getName());
                    scopeSaveCalls[0]++;
                    Collection<?> scopes = (Collection<?>) args[0];
                    assertEquals(2, scopes.size());
                    for (Object value : scopes) {
                        CouponScope scope = (CouponScope) value;
                        assertEquals(42L, scope.getCouponId());
                        assertTrue(form.getScopes().contains(scope.getBizId()));
                    }
                    return true;
                });
        RecordingService service = new RecordingService(scopeService);
        service.saveCoupon(form);
        assertEquals(Boolean.TRUE, service.saved.getSpecific());
        assertEquals(1, scopeSaveCalls[0]);
    }

    @Test
    void scopeElementsMustBeNonNullAndPositive() {
        for (Long scopeId : Arrays.asList(null, 0L, -1L)) {
            CouponFormDTO form = validForm();
            form.setSpecific(true);
            form.setScopes(Collections.singletonList(scopeId));
            assertTrue(validator.validate(form).stream()
                    .anyMatch(violation -> violation.getPropertyPath().toString().startsWith("scopes[0]")),
                    "Invalid scope ID: " + scopeId);
        }
    }

    @Test
    void validScopeIdsPassValidation() {
        CouponFormDTO form = validForm();
        form.setSpecific(true);
        form.setScopes(Arrays.asList(1L, Long.MAX_VALUE));
        assertTrue(validator.validate(form).isEmpty());
    }

    private static void assertInvalidField(CouponFormDTO form, String field) {
        assertTrue(validator.validate(form).stream()
                .anyMatch(violation -> field.equals(violation.getPropertyPath().toString())), field);
    }

    private static void assertRejectedBeforeSaving(CouponFormDTO form) {
        RecordingService service = new RecordingService(null);
        assertThrows(BadRequestException.class, () -> service.saveCoupon(form));
        assertNull(service.saved);
    }

    private static CouponFormDTO validForm() {
        CouponFormDTO form = new CouponFormDTO();
        form.setName("test-coupon");
        form.setDiscountType(DiscountType.RATE_DISCOUNT);
        form.setDiscountValue(80);
        form.setTotalNum(100);
        form.setObtainWay(ObtainType.PUBLIC);
        return form;
    }

    private static class RecordingService extends CouponServiceImpl {
        private Coupon saved;

        RecordingService(ICouponScopeService scopeService) {
            super(scopeService, null, null);
        }

        @Override
        public boolean save(Coupon coupon) {
            saved = coupon;
            coupon.setId(42L);
            return true;
        }
    }
}
