package com.tianji.trade.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.utils.UserContext;
import com.tianji.trade.config.TradeProperties;
import com.tianji.trade.domain.dto.PlaceOrderDTO;
import com.tianji.trade.domain.po.Order;
import com.tianji.trade.domain.po.OrderDetail;
import com.tianji.trade.service.IOrderDetailService;
import com.tianji.trade.service.IOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SandboxCheckoutServiceTest {
    private final CourseClient courses = mock(CourseClient.class);
    private final IOrderService orders = mock(IOrderService.class);
    private final IOrderDetailService details = mock(IOrderDetailService.class);
    private final AlipaySandboxMcp alipay = mock(AlipaySandboxMcp.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> cache = new HashMap<>();
    private final Map<Long, Order> stored = new HashMap<>();
    private CourseSimpleInfoDTO course;
    private SandboxCheckoutService service;

    @BeforeEach void setup() {
        UserContext.setUser(28L);
        var settings = new SandboxCheckoutProperties();
        settings.setAllowedUserIds(Set.of(28L, 29L));
        settings.setAllowedCourseIds(Set.of(111L));
        service = new SandboxCheckoutService(settings, courses, orders, details, new TradeProperties(), alipay,
                redis, redisson, new ObjectMapper().findAndRegisterModules());
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> cache.get(i.getArgument(0)));
        doAnswer(i -> { cache.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        var lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        course = new CourseSimpleInfoDTO();
        course.setId(111L); course.setName("fixture course"); course.setPrice(14900); course.setFree(false); course.setStatus(2);
        course.setPurchaseStartTime(LocalDateTime.now().minusDays(1)); course.setPurchaseEndTime(LocalDateTime.now().plusDays(1));
        when(courses.getSimpleInfoList(List.of(111L))).thenReturn(List.of(course));
        when(orders.getById(any())).thenAnswer(i -> stored.get(i.getArgument(0)));
        when(orders.placeOrder(any())).thenAnswer(i -> {
            PlaceOrderDTO dto = i.getArgument(0);
            stored.put(dto.getOrderId(), new Order().setId(dto.getOrderId()).setUserId(28L).setStatus(1)
                    .setRealAmount(course.getPrice()).setCreateTime(LocalDateTime.now()));
            return null;
        });
        var detail = new OrderDetail(); detail.setCourseId(111L);
        when(details.queryByOrderId(anyLong())).thenReturn(List.of(detail));
        when(alipay.create(anyString(), eq(14900), anyString())).thenReturn("https://openapi.alipaydev.com/gateway.do?fixture");
    }

    @AfterEach void clear() { UserContext.removeUser(); }

    @Test void previewDoesNotCreateOrderOrInvokeProvider() {
        var quote = service.preview(111L);
        assertEquals(14900, quote.amountInCents());
        assertNull(quote.orderId());
        verifyNoInteractions(orders, alipay);
    }

    @Test void confirmationCreatesOneServerPricedOrderAndIsIdempotent() {
        var preview = service.preview(111L);
        var first = service.confirm(preview.token(), true);
        var repeated = service.confirm(preview.token(), true);
        assertEquals(first, repeated);
        assertNotNull(first.orderId());
        verify(orders, times(1)).placeOrder(any());
        verify(alipay, times(1)).create(first.orderId(), 14900, "fixture course");
    }

    @Test void rejectsMissingConfirmationOrOtherUsersQuote() {
        var quote = service.preview(111L);
        assertThrows(Exception.class, () -> service.confirm(quote.token(), false));
        UserContext.setUser(29L);
        assertThrows(Exception.class, () -> service.confirm(quote.token(), true));
        verifyNoInteractions(orders, alipay);
    }

    @Test void rejectsOutOfScopeUserCourseAndClosedSale() {
        assertThrows(Exception.class, () -> service.preview(222L));
        UserContext.setUser(99L);
        assertThrows(Exception.class, () -> service.preview(111L));
        UserContext.setUser(28L); course.setStatus(3);
        assertThrows(Exception.class, () -> service.preview(111L));
        verifyNoInteractions(orders, alipay);
    }

    @Test void changedPriceDoesNotCreateAnOrderOrPayment() {
        var quote = service.preview(111L);
        course.setPrice(19900);
        assertThrows(Exception.class, () -> service.confirm(quote.token(), true));
        verify(orders, never()).placeOrder(any());
        verifyNoInteractions(alipay);
    }

    @Test void legacySaleMayOmitStartButExplicitFutureStartAndMissingEndAreRejected() {
        course.setPurchaseStartTime(null);
        assertEquals(14900, service.preview(111L).amountInCents());
        course.setPurchaseStartTime(LocalDateTime.now().plusDays(1));
        assertThrows(Exception.class, () -> service.preview(111L));
        course.setPurchaseStartTime(null); course.setPurchaseEndTime(null);
        assertThrows(Exception.class, () -> service.preview(111L));
        verifyNoInteractions(orders, alipay);
    }

    @Test void statusBeforeConfirmationDoesNotContactProvider() {
        var quote = service.preview(111L);
        assertEquals("AWAITING_CONFIRMATION", service.status(quote.token()).status());
        verifyNoInteractions(alipay);
    }

    @Test void sandboxSuccessDoesNotFulfilOrdinaryCourses() {
        var confirmed = service.confirm(service.preview(111L).token(), true);
        when(alipay.query(confirmed.orderId(), 14900)).thenReturn(new AlipaySandboxMcp.PaymentState("TRADE_SUCCESS", "12345"));
        assertEquals("TRADE_SUCCESS", service.status(confirmed.token()).status());
        verify(orders, never()).handlePaySuccess(any());
        assertEquals(1, stored.get(Long.valueOf(confirmed.orderId())).getStatus());
    }
}
