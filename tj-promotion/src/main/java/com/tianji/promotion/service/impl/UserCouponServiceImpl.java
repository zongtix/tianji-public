package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.UserCouponPageVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户优惠券服务实现
 */
@Service
@RequiredArgsConstructor
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {
    private final CouponMapper couponMapper;
    private final ExchangeCodeMapper exchangeCodeMapper;

    @Override
    public PageDTO<UserCouponPageVO> queryUserCouponPage(UserCouponQuery query) {
        Long userId = UserContext.getUser();
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("请先登录后再查询优惠券");
        }
        UserCouponStatus status = UserCouponStatus.of(query.getStatus());
        if (status == null) {
            throw new BadRequestException("优惠券状态不正确");
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryChainWrapper<UserCoupon> wrapper = lambdaQuery().eq(UserCoupon::getUserId, userId);
        if (status == UserCouponStatus.EXPIRED) {
            wrapper.and(condition -> condition.eq(UserCoupon::getStatus, UserCouponStatus.EXPIRED)
                    .or(expired -> expired.eq(UserCoupon::getStatus, UserCouponStatus.UNUSED)
                            .le(UserCoupon::getTermEndTime, now)));
        } else {
            wrapper.eq(UserCoupon::getStatus, status);
            if (status == UserCouponStatus.UNUSED) {
                wrapper.gt(UserCoupon::getTermEndTime, now);
            }
        }
        Page<UserCoupon> page = wrapper.page(query.toMpPage(OrderItem.desc("create_time"), OrderItem.desc("id")));
        if (page.getRecords().isEmpty()) {
            return PageDTO.empty(page);
        }
        List<Long> couponIds = page.getRecords().stream().map(UserCoupon::getCouponId).distinct().collect(Collectors.toList());
        Map<Long, Coupon> coupons = couponMapper.selectByIds(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, Function.identity()));
        return PageDTO.of(page, record -> {
            UserCouponStatus displayStatus = record.getStatus() == UserCouponStatus.UNUSED
                    && !record.getTermEndTime().isAfter(now) ? UserCouponStatus.EXPIRED : record.getStatus();
            UserCouponPageVO result = new UserCouponPageVO().setId(record.getId()).setCouponId(record.getCouponId())
                    .setTermBeginTime(record.getTermBeginTime()).setTermEndTime(record.getTermEndTime())
                    .setStatus(displayStatus).setUsedTime(record.getUsedTime()).setCreateTime(record.getCreateTime());
            Coupon coupon = coupons.get(record.getCouponId());
            if (coupon == null) {
                return result.setName("优惠券信息不可用");
            }
            return result.setName(coupon.getName()).setSpecific(coupon.getSpecific()).setDiscountType(coupon.getDiscountType())
                    .setThresholdAmount(coupon.getThresholdAmount()).setDiscountValue(coupon.getDiscountValue())
                    .setMaxDiscountAmount(coupon.getMaxDiscountAmount());
        });
    }

    @Override
    @Transactional
    public void receiveCoupon(Long couponId) {
        receiveCouponForUser(couponId, requireUser());
    }

    @Override
    @Transactional
    public Long receiveCouponForUser(Long couponId, Long userId) {
        requireUserId(userId);
        // 同一张优惠券的领取在数据库中串行执行，锁一直持有到计数和领券记录一起提交。
        Coupon coupon = lockCoupon(couponId);
        return issueUserCoupon(coupon, userId, ObtainType.PUBLIC,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @Override
    @Transactional
    public void exchangeCoupon(String code) {
        exchangeCouponForUser(code, requireUser());
    }

    @Override
    @Transactional
    public Long exchangeCouponForUser(String code, Long userId) {
        requireUserId(userId);
        long serial = CodeUtil.parseCode(code);
        // 当前兑换码表使用有符号 Integer 主键，不能直接截断解析得到的无符号序列号。
        if (serial <= 0 || serial > Integer.MAX_VALUE) {
            throw new BadRequestException("无效兑换码");
        }
        ExchangeCode snapshot = exchangeCodeMapper.selectById((int) serial);
        if (snapshot == null || !code.equals(snapshot.getCode())
                || !Integer.valueOf(1).equals(snapshot.getType()) || snapshot.getExchangeTargetId() == null) {
            throw new BadRequestException("无效兑换码");
        }
        // 统一先锁优惠券、再锁兑换码；首次查询只用于定位，状态以锁定读结果为准。
        Coupon coupon = lockCoupon(snapshot.getExchangeTargetId());
        ExchangeCode exchangeCode = exchangeCodeMapper.selectOne(Wrappers.<ExchangeCode>lambdaQuery()
                .eq(ExchangeCode::getId, (int) serial).last("FOR UPDATE"));
        if (exchangeCode == null || !code.equals(exchangeCode.getCode())
                || !Integer.valueOf(1).equals(exchangeCode.getType())
                || !Objects.equals(exchangeCode.getExchangeTargetId(), coupon.getId())) {
            throw new BadRequestException("无效兑换码");
        }
        // 保留时间精度，避免截断到秒后放行刚刚过期的兑换码或优惠券。
        LocalDateTime now = LocalDateTime.now();
        if (exchangeCode.getStatus() == ExchangeCodeStatus.USED) {
            throw new BadRequestException("兑换码已兑换");
        }
        if (exchangeCode.getStatus() != ExchangeCodeStatus.UNUSED || exchangeCode.getExpiredTime() == null
                || !exchangeCode.getExpiredTime().isAfter(now)) {
            throw new BadRequestException("兑换码已失效");
        }
        int updated = exchangeCodeMapper.update(null, Wrappers.<ExchangeCode>lambdaUpdate()
                .eq(ExchangeCode::getId, exchangeCode.getId()).eq(ExchangeCode::getStatus, ExchangeCodeStatus.UNUSED)
                .gt(ExchangeCode::getExpiredTime, now).set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                .set(ExchangeCode::getUserId, userId).set(ExchangeCode::getUpdateTime, now));
        if (updated != 1) {
            throw new BadRequestException("兑换码已兑换或已失效");
        }
        // 后续校验或发券失败会一起回滚核销结果，不会消耗兑换码。
        return issueUserCoupon(coupon, userId, ObtainType.ISSUE, now);
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("请先登录后再领取优惠券");
        }
    }

    private Long requireUser() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("请先登录后再领取优惠券");
        }
        return userId;
    }

    private Coupon lockCoupon(Long couponId) {
        Coupon coupon = couponMapper.selectOne(Wrappers.<Coupon>lambdaQuery()
                .eq(Coupon::getId, couponId).last("FOR UPDATE"));
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
        return coupon;
    }

    /** 调用方须在当前事务内持有优惠券行锁，保证库存与累计限领检查串行执行。 */
    private Long issueUserCoupon(Coupon coupon, Long userId, ObtainType obtainType, LocalDateTime now) {
        Long couponId = coupon.getId();
        if (coupon.getStatus() != CouponStatus.ISSUING || coupon.getObtainWay() != obtainType) {
            throw new BadRequestException("该优惠券暂不可领取");
        }
        if (coupon.getIssueBeginTime() == null || coupon.getIssueBeginTime().isAfter(now)
                || coupon.getIssueEndTime() == null || !coupon.getIssueEndTime().isAfter(now)) {
            throw new BadRequestException("当前不在优惠券发放时间内");
        }
        if (coupon.getIssueNum() >= coupon.getTotalNum()) {
            throw new BadRequestException("优惠券已领完");
        }
        // 锁定读使用最新记录；已使用和已失效的券仍计入累计领取数量。
        int received = lambdaQuery().select(UserCoupon::getId)
                .eq(UserCoupon::getUserId, userId).eq(UserCoupon::getCouponId, couponId)
                .last("FOR UPDATE").list().size();
        if (received >= coupon.getUserLimit()) {
            throw new BadRequestException("已达到该优惠券的领取上限");
        }

        LocalDateTime termBegin = coupon.getTermDays() > 0 ? now : coupon.getTermBeginTime();
        LocalDateTime termEnd = coupon.getTermDays() > 0 ? now.plusDays(coupon.getTermDays()) : coupon.getTermEndTime();
        if (termBegin == null || termEnd == null || !termEnd.isAfter(termBegin) || !termEnd.isAfter(now)) {
            throw new BadRequestException("优惠券使用有效期不可用");
        }
        int updated = couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
                .eq(Coupon::getId, couponId).lt(Coupon::getIssueNum, coupon.getTotalNum())
                .setSql("issue_num = issue_num + 1").set(Coupon::getUpdateTime, now));
        if (updated != 1) {
            throw new BadRequestException("优惠券已领完");
        }
        UserCoupon record = new UserCoupon().setUserId(userId).setCouponId(couponId)
                .setStatus(UserCouponStatus.UNUSED).setTermBeginTime(termBegin).setTermEndTime(termEnd)
                .setCreateTime(now).setUpdateTime(now);
        if (!save(record)) {
            throw new DbException("领取优惠券失败");
        }
        return record.getId();
    }
}
