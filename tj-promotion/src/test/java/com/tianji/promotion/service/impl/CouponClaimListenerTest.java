package com.tianji.promotion.service.impl;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.promotion.mq.CouponClaimListener;
import com.tianji.promotion.service.claim.CouponClaimProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class CouponClaimListenerTest {
    @Test
    void databaseOutageDuringProcessingAndFailureRecordingDoesNotHotLoopRequeue() {
        CouponClaimProcessor processor = mock(CouponClaimProcessor.class);
        doThrow(new IllegalStateException("database unavailable")).when(processor).process("request-001");
        doThrow(new IllegalStateException("database unavailable")).when(processor)
                .recordFailure("request-001", false, null);
        assertDoesNotThrow(() -> new CouponClaimListener(processor).onMessage("request-001"));
        verify(processor).recordFailure("request-001", false, null);
    }

    @Test
    void businessFailureWithUnavailableFailureStorageStillAllowsDurableRecovery() {
        CouponClaimProcessor processor = mock(CouponClaimProcessor.class);
        doThrow(new BadRequestException("已达到该优惠券的领取上限")).when(processor).process("request-001");
        doThrow(new IllegalStateException("database unavailable")).when(processor)
                .recordFailure("request-001", true, "已达到该优惠券的领取上限");
        assertDoesNotThrow(() -> new CouponClaimListener(processor).onMessage("request-001"));
    }
}
