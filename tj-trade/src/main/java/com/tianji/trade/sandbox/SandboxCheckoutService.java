package com.tianji.trade.sandbox;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.constants.CourseStatus;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.ForbiddenException;
import com.tianji.common.utils.UserContext;
import com.tianji.trade.config.TradeProperties;
import com.tianji.trade.constants.OrderStatus;
import com.tianji.trade.domain.dto.PlaceOrderDTO;
import com.tianji.trade.domain.po.Order;
import com.tianji.trade.service.IOrderDetailService;
import com.tianji.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Explicit confirmation of a server-priced, sandbox-only purchase. Never grants real access. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tj.trade.sandbox-checkout.enabled", havingValue = "true")
public class SandboxCheckoutService {
    private static final String PREFIX = "trade:alipay:sandbox:quote:";
    private final SandboxCheckoutProperties settings;
    private final CourseClient courses;
    private final IOrderService orders;
    private final IOrderDetailService details;
    private final TradeProperties trade;
    private final AlipaySandboxMcp alipay;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper json;

    public View preview(Long courseId) {
        Long user = user();
        var course = course(courseId);
        String token = UUID.randomUUID().toString().replace("-", "");
        var quote = new Quote(token, user, courseId, IdWorker.getId(), course.getName(), course.getPrice(),
                Instant.now().plusSeconds(600), null, "AWAITING_CONFIRMATION");
        save(quote);
        return view(quote, false);
    }

    public View confirm(String token, boolean confirmed) {
        if (!confirmed) throw new BizIllegalException("请先确认课程和金额。");
        return locked(token, quote -> {
            if (quote.expiresAt().isBefore(Instant.now().plusSeconds(60))) throw expired();
            var order = orders.getById(quote.orderId());
            if (order == null) {
                if (!course(quote.courseId()).getPrice().equals(quote.cents())) throw new BizIllegalException("课程价格已变化，请重新确认。");
                var request = new PlaceOrderDTO();
                request.setOrderId(quote.orderId());
                request.setCourseIds(List.of(quote.courseId()));
                orders.placeOrder(request);
                order = orders.getById(quote.orderId());
            }
            requireOrder(quote, order);
            if (quote.paymentUrl() != null) return view(quote, true);
            String url = alipay.create(quote.orderId().toString(), quote.cents(), quote.courseName());
            var linked = new Quote(quote.token(), quote.userId(), quote.courseId(), quote.orderId(), quote.courseName(),
                    quote.cents(), quote.expiresAt(), url, "PAYMENT_LINK_READY");
            save(linked);
            return view(linked, true);
        });
    }

    public View status(String token) {
        return locked(token, quote -> {
            if (quote.paymentUrl() == null) return view(quote, false);
            requireOrder(quote, orders.getById(quote.orderId()));
            var state = alipay.query(quote.orderId().toString(), quote.cents());
            var updated = new Quote(quote.token(), quote.userId(), quote.courseId(), quote.orderId(), quote.courseName(),
                    quote.cents(), quote.expiresAt(), quote.paymentUrl(), state.status());
            save(updated);
            // Virtual sandbox funds never update ordinary paid/enrolment state or publish fulfilment MQ.
            return view(updated, true);
        });
    }

    private Long user() {
        Long id = UserContext.getUser();
        if (id == null || !settings.getAllowedUserIds().contains(id)) throw new ForbiddenException("当前账户未开放沙箱试点。");
        return id;
    }

    private CourseSimpleInfoDTO course(Long id) {
        if (id == null || !settings.getAllowedCourseIds().contains(id)) throw new ForbiddenException("该课程不在沙箱试点范围。");
        var found = courses.getSimpleInfoList(List.of(id));
        if (found == null || found.size() != 1 || !id.equals(found.get(0).getId())) throw new BizIllegalException("课程暂不可购买。");
        var course = found.get(0);
        if (!CourseStatus.SHELF.equalsValue(course.getStatus()) || Boolean.TRUE.equals(course.getFree())
                || course.getPrice() == null || course.getPrice() <= 0 || course.getName() == null || course.getName().isBlank()
                || course.getPurchaseEndTime() == null
                || (course.getPurchaseStartTime() != null && course.getPurchaseStartTime().isAfter(LocalDateTime.now()))
                || !course.getPurchaseEndTime().isAfter(LocalDateTime.now())) {
            throw new BizIllegalException("课程暂不可购买。");
        }
        return course;
    }

    private void requireOrder(Quote quote, Order order) {
        if (order == null || !quote.userId().equals(order.getUserId())) throw new ForbiddenException("订单不存在或不属于当前账户。");
        if (!OrderStatus.NO_PAY.equalsValue(order.getStatus()) || order.getCreateTime() == null
                || !order.getCreateTime().plusMinutes(trade.getPayOrderTTLMinutes()).isAfter(LocalDateTime.now())) throw expired();
        var items = details.queryByOrderId(order.getId());
        if (!Integer.valueOf(quote.cents()).equals(order.getRealAmount()) || items == null || items.size() != 1
                || !quote.courseId().equals(items.get(0).getCourseId())) throw new BizIllegalException("订单内容已变化，请重新确认。");
    }

    private View locked(String token, Function<Quote, View> action) {
        var quote = load(token);
        var lock = redisson.getLock(PREFIX + "lock:" + token);
        if (!lock.tryLock()) throw new BizIllegalException("订单正在处理中，请稍后重试。");
        try { return action.apply(load(quote.token())); }
        finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private Quote load(String token) {
        Long user = user();
        if (token == null || !token.matches("[a-f0-9]{32}")) throw expired();
        String value = redis.opsForValue().get(PREFIX + token);
        if (value == null) throw expired();
        try {
            var quote = json.readValue(value, Quote.class);
            if (!user.equals(quote.userId())) throw new ForbiddenException("确认信息不属于当前账户。");
            if (!quote.expiresAt().isAfter(Instant.now())) throw expired();
            return quote;
        } catch (BizIllegalException | ForbiddenException error) { throw error; }
        catch (Exception ignored) { throw new BizIllegalException("订单确认信息暂不可用，请重新选择。"); }
    }

    private void save(Quote quote) {
        Duration ttl = Duration.between(Instant.now(), quote.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) throw expired();
        try { redis.opsForValue().set(PREFIX + quote.token(), json.writeValueAsString(quote), ttl); }
        catch (Exception ignored) { throw new BizIllegalException("订单确认信息暂不可用，请稍后重试。"); }
    }

    private View view(Quote quote, boolean created) {
        return new View(quote.token(), quote.courseId().toString(), quote.courseName(), quote.cents(),
                created ? quote.orderId().toString() : null, quote.paymentUrl(), quote.status());
    }

    private static BizIllegalException expired() { return new BizIllegalException("订单或确认信息已过期，请重新选择课程。"); }
    record Quote(String token, Long userId, Long courseId, Long orderId, String courseName, int cents,
                 Instant expiresAt, String paymentUrl, String status) { }
    public record View(String token, String courseId, String courseName, int amountInCents, String orderId,
                       String paymentUrl, String status) { }
}
