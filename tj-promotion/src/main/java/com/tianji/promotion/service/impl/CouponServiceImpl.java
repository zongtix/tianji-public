package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 优惠券规则服务实现
 */
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    private final ICouponScopeService scopeService;
    private final IExchangeCodeService exchangeCodeService;
    private final IUserCouponService userCouponService;

    @Override
    @Transactional
    public void saveCoupon(CouponFormDTO couponFormDTO) {
        //1、写库前校验折扣规则和限定范围
        DiscountType discountType = couponFormDTO.getDiscountType();
        int thresholdAmount = couponFormDTO.getThresholdAmount();
        if (discountType == DiscountType.RATE_DISCOUNT && couponFormDTO.getDiscountValue() >= 100) {
            throw new BadRequestException("折扣值必须在1~99之间");
        }
        if ((discountType == DiscountType.PER_PRICE_DISCOUNT || discountType == DiscountType.PRICE_DISCOUNT)
                && thresholdAmount == 0) {
            throw new BadRequestException("满减优惠券的折扣门槛必须大于0");
        }
        if (discountType == DiscountType.NO_THRESHOLD && thresholdAmount != 0) {
            throw new BadRequestException("无门槛优惠券的折扣门槛必须为0");
        }
        boolean specific = Boolean.TRUE.equals(couponFormDTO.getSpecific());
        List<Long> scopes = couponFormDTO.getScopes();
        if (specific && CollUtils.isEmpty(scopes)) {
            throw new BadRequestException("优惠券限定范围不能为空");
        }

        //2、保存优惠券，将省略或为null的范围标志统一为false
        Coupon coupon = BeanUtils.copyProperties(couponFormDTO, Coupon.class);
        coupon.setSpecific(specific);
        save(coupon);
        if (!specific) {
            return;
        }

        //3、保存限定范围
        List<CouponScope> list = scopes.stream()
                .map(bizId -> new CouponScope().setBizId(bizId).setCouponId(coupon.getId()))
                .collect(Collectors.toList());
        scopeService.saveBatch(list);
    }

    @Override
    public PageDTO<CouponPageVO> queryCouponPage(CouponQuery query) {
        String name = query.getName() == null ? null : query.getName().trim();
        // type表示折扣类型；固定排序避免将请求中的sortBy直接拼入SQL。
        Page<Coupon> page = lambdaQuery()
                .like(StringUtils.isNotBlank(name), Coupon::getName, name)
                .eq(query.getType() != null, Coupon::getDiscountType, query.getType())
                .eq(query.getStatus() != null, Coupon::getStatus, query.getStatus())
                .page(query.toMpPage(OrderItem.desc("create_time"), OrderItem.desc("id")));
        return PageDTO.of(page, CouponPageVO.class);
    }

    @Override
    public List<CouponVO> queryIssuingCoupons() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        // 同时校验发放窗口，避免定时任务尚未推进状态时展示已结束的券。
        List<Coupon> coupons = lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                .le(Coupon::getIssueBeginTime, now)
                .gt(Coupon::getIssueEndTime, now)
                .orderByDesc(Coupon::getIssueBeginTime, Coupon::getId)
                .list();
        if (CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }

        Long userId = UserContext.getUser();
        Map<Long, List<UserCoupon>> recordsByCoupon;
        if (userId != null) {
            List<Long> couponIds = coupons.stream().map(Coupon::getId).collect(Collectors.toList());
            // 不按使用状态过滤：已使用、已失效的券也占用累计领取名额。
            List<UserCoupon> records = userCouponService.lambdaQuery()
                    .select(UserCoupon::getCouponId, UserCoupon::getStatus,
                            UserCoupon::getTermBeginTime, UserCoupon::getTermEndTime)
                    .eq(UserCoupon::getUserId, userId)
                    .in(UserCoupon::getCouponId, couponIds)
                    .list();
            recordsByCoupon = records.stream().collect(Collectors.groupingBy(UserCoupon::getCouponId));
        } else {
            recordsByCoupon = Collections.emptyMap();
        }

        return BeanUtils.copyList(coupons, CouponVO.class, (coupon, vo) -> {
            List<UserCoupon> records = recordsByCoupon.getOrDefault(coupon.getId(), Collections.emptyList());
            vo.setAvailable(coupon.getIssueNum() < coupon.getTotalNum()
                    && records.size() < coupon.getUserLimit());
            // received 表示持有当前可使用的券，区别于历史上领取过。
            vo.setReceived(records.stream().anyMatch(record -> record.getStatus() == UserCouponStatus.UNUSED
                    && (record.getTermBeginTime() == null || !record.getTermBeginTime().isAfter(now))
                    && record.getTermEndTime().isAfter(now)));
        });
    }

    @Override
    @Transactional
    public void issueCoupon(Long id, CouponIssueDTO dto) {
        Coupon coupon = getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
        if (coupon.getStatus() != CouponStatus.DRAFT) {
            throw new BadRequestException("只有草稿状态的优惠券可以发放");
        }
        if (coupon.getObtainWay() != ObtainType.PUBLIC && coupon.getObtainWay() != ObtainType.ISSUE) {
            throw new BadRequestException("优惠券领取方式不正确");
        }
        // 与数据库datetime的秒精度一致，一次操作使用同一个当前时间。
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime begin = dto.getIssueBeginTime();
        if (begin != null && !begin.isAfter(now)) {
            throw new BadRequestException("指定的发放开始时间必须晚于当前时间");
        }
        begin = begin == null ? now : begin;
        LocalDateTime end = dto.getIssueEndTime();
        if (end == null || !end.isAfter(begin)) {
            throw new BadRequestException("发放结束时间必须晚于开始时间和当前时间");
        }
        Integer termDays = dto.getTermDays();
        if (termDays == null || termDays < 0) {
            throw new BadRequestException("有效期天数不能为空或为负数");
        }
        if (termDays > 0) {
            if (dto.getTermBeginTime() != null || dto.getTermEndTime() != null) {
                throw new BadRequestException("领取后有效天数与固定有效期不能同时指定");
            }
        } else {
            if (dto.getTermBeginTime() == null || dto.getTermEndTime() == null
                    || !dto.getTermEndTime().isAfter(dto.getTermBeginTime())) {
                throw new BadRequestException("固定有效期起止时间不能为空，且结束时间必须晚于开始时间");
            }
            if (dto.getTermEndTime().isBefore(end)) {
                throw new BadRequestException("有效期结束时间不能早于发放结束时间");
            }
        }
        Long userId = UserContext.getUser();
        // 条件更新防止两个请求同时读取到草稿后覆盖配置；显式set允许清除旧的固定有效期。
        boolean updated = lambdaUpdate()
                .eq(Coupon::getId, id)
                .eq(Coupon::getStatus, CouponStatus.DRAFT)
                .set(Coupon::getIssueBeginTime, begin)
                .set(Coupon::getIssueEndTime, end)
                .set(Coupon::getTermDays, termDays)
                .set(Coupon::getTermBeginTime, dto.getTermBeginTime())
                .set(Coupon::getTermEndTime, dto.getTermEndTime())
                .set(Coupon::getStatus, dto.getIssueBeginTime() == null ? CouponStatus.ISSUING : CouponStatus.UN_ISSUE)
                .set(Coupon::getUpdateTime, now)
                .set(userId != null, Coupon::getUpdater, userId)
                .update();
        if (!updated) {
            throw new BadRequestException("优惠券状态已变更，请刷新后重试");
        }
        if (coupon.getObtainWay() == ObtainType.ISSUE) {
            // 只传递生成所需的快照，结束时间使用本次发放的新配置。
            Coupon snapshot = new Coupon().setId(id).setTotalNum(coupon.getTotalNum()).setIssueEndTime(end);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    exchangeCodeService.asyncGenerateCode(snapshot);
                }
            });
        }
    }

    @Override
    @Transactional
    public void refreshCouponStatus() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        // 先结束再开始，服务停机跨过整个发放窗口后不会误激活已经过期的券。
        lambdaUpdate()
                .in(Coupon::getStatus, CouponStatus.UN_ISSUE, CouponStatus.ISSUING, CouponStatus.PAUSE)
                .le(Coupon::getIssueEndTime, now)
                .set(Coupon::getStatus, CouponStatus.FINISHED)
                .set(Coupon::getUpdateTime, now)
                .update();
        lambdaUpdate()
                .eq(Coupon::getStatus, CouponStatus.UN_ISSUE)
                .le(Coupon::getIssueBeginTime, now)
                .gt(Coupon::getIssueEndTime, now)
                .set(Coupon::getStatus, CouponStatus.ISSUING)
                .set(Coupon::getUpdateTime, now)
                .update();
    }
}
