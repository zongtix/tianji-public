package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.constants.CourseStatus;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.dto.CouponRecommendationDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.vo.CouponRecommendationVO;
import com.tianji.promotion.domain.vo.CouponUsageVO;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.CouponScopeMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于用户真实持有优惠券的确定性推荐。
 *
 * <p>候选券只来自当前用户状态为未使用、且在有效期内的 user_coupon 记录；
 * 课程价格与分类来自课程服务，优惠券范围只读取 coupon_scope。算法不使用随机数或模型推断。</p>
 *
 * <p>规模边界：最多扫描 {@value #MAX_CANDIDATE_RECORDS} 条用户券，最多保留
 * {@value #MAX_CANDIDATES} 个候选券，每个方案最多使用 {@value #MAX_COUPONS_PER_PLAN} 张券。
 * 20 个候选、3 张券以内的全部组合与顺序一共 7241 种，搜索空间有明确上限。</p>
 */
@Service
@RequiredArgsConstructor
public class CouponRecommendationServiceImpl implements ICouponRecommendationService {

    static final int MAX_COURSES_PER_REQUEST = 10;
    static final int MAX_CANDIDATE_RECORDS = 200;
    static final int MAX_CANDIDATES = 20;
    static final int MAX_COUPONS_PER_PLAN = 3;

    private static final int SCOPE_TYPE_CATEGORY = 1;
    private static final int SCOPE_TYPE_COURSE = 2;

    private final CourseClient courseClient;
    private final UserCouponMapper userCouponMapper;
    private final CouponMapper couponMapper;
    private final CouponScopeMapper couponScopeMapper;

    @Override
    public CouponRecommendationVO recommend(CouponRecommendationDTO dto) {
        Long userId = requireUser();
        List<OrderItem> items = loadOrderItems(dto == null ? null : dto.getCourseIds());
        long totalAmount = items.stream().mapToLong(OrderItem::getPrice).sum();
        if (totalAmount > Integer.MAX_VALUE) {
            throw new BadRequestException("课程总金额超出可计算范围");
        }
        int originalAmount = (int) totalAmount;
        if (originalAmount <= 0) {
            return emptyResult(originalAmount);
        }

        List<Candidate> candidates = loadCandidates(userId, items);
        if (candidates.isEmpty()) {
            return emptyResult(originalAmount);
        }
        candidates.sort(Comparator
                .comparingInt((Candidate candidate) -> candidate.initialDiscount).reversed()
                .thenComparingLong(candidate -> candidate.userCoupon.getId()));
        if (candidates.size() > MAX_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }
        return toVO(originalAmount, search(candidates, items));
    }

    private Long requireUser() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("请先登录后再获取优惠券推荐");
        }
        return userId;
    }

    private List<OrderItem> loadOrderItems(List<Long> courseIds) {
        if (CollUtils.isEmpty(courseIds)) {
            throw new BadRequestException("请选择要购买的课程");
        }
        if (courseIds.size() > MAX_COURSES_PER_REQUEST) {
            throw new BadRequestException("一次最多选购10门课程");
        }
        if (new HashSet<>(courseIds).size() != courseIds.size()) {
            throw new BadRequestException("课程不能重复");
        }
        for (Long courseId : courseIds) {
            if (courseId == null || courseId <= 0) {
                throw new BadRequestException("课程id不合法");
            }
        }
        List<CourseSimpleInfoDTO> courses = courseClient.getSimpleInfoList(courseIds);
        Map<Long, CourseSimpleInfoDTO> courseMap = new HashMap<>();
        if (courses != null) {
            for (CourseSimpleInfoDTO course : courses) {
                if (course != null && course.getId() != null) {
                    courseMap.put(course.getId(), course);
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<OrderItem> items = new ArrayList<>(courseIds.size());
        for (Long courseId : courseIds) {
            CourseSimpleInfoDTO course = courseMap.get(courseId);
            if (course == null) {
                throw new BadRequestException("课程不存在：" + courseId);
            }
            if (!CourseStatus.SHELF.equalsValue(course.getStatus())) {
                throw new BadRequestException("课程不可购买：" + courseId);
            }
            if (course.getPurchaseEndTime() != null && !course.getPurchaseEndTime().isAfter(now)) {
                throw new BadRequestException("课程购买期限已过：" + courseId);
            }
            if (course.getPrice() == null || course.getPrice() < 0) {
                throw new BadRequestException("课程价格异常：" + courseId);
            }
            Set<Long> categoryIds = new HashSet<>();
            addIfNotNull(categoryIds, course.getFirstCateId());
            addIfNotNull(categoryIds, course.getSecondCateId());
            addIfNotNull(categoryIds, course.getThirdCateId());
            items.add(new OrderItem(courseId, course.getPrice(), categoryIds));
        }
        items.sort(Comparator.comparingLong(OrderItem::getCourseId));
        return items;
    }

    private List<Candidate> loadCandidates(Long userId, List<OrderItem> items) {
        List<UserCoupon> records = loadValidUserCoupons(userId);
        if (records.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> couponIds = records.stream().map(UserCoupon::getCouponId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (couponIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Coupon> coupons = new HashMap<>();
        List<Coupon> couponList = couponMapper.selectList(Wrappers.<Coupon>lambdaQuery()
                .in(Coupon::getId, couponIds));
        if (couponList != null) {
            for (Coupon coupon : couponList) {
                if (coupon != null) {
                    coupons.put(coupon.getId(), coupon);
                }
            }
        }

        Map<Long, List<CouponScope>> scopes = new HashMap<>();
        List<CouponScope> scopeList = couponScopeMapper.selectList(Wrappers.<CouponScope>lambdaQuery()
                .in(CouponScope::getCouponId, couponIds));
        if (scopeList != null) {
            for (CouponScope scope : scopeList) {
                scopes.computeIfAbsent(scope.getCouponId(), key -> new ArrayList<>()).add(scope);
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (UserCoupon record : records) {
            Coupon coupon = coupons.get(record.getCouponId());
            if (coupon == null || !hasUsableRule(coupon)) {
                continue;
            }
            boolean specific = Boolean.TRUE.equals(coupon.getSpecific());
            List<CouponScope> couponScopes = scopes.getOrDefault(coupon.getId(), Collections.emptyList());
            boolean[] matches = matchItems(specific, couponScopes, items);
            if (!hasMatch(matches)) {
                continue;
            }
            Candidate candidate = new Candidate(record, coupon, couponScopes, matches);
            candidate.initialDiscount = calculateDiscount(coupon, applicableAmount(matches, remaining(items)));
            if (candidate.initialDiscount > 0) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private List<UserCoupon> loadValidUserCoupons(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<UserCoupon> records = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, UserCouponStatus.UNUSED)
                .and(wrapper -> wrapper.isNull(UserCoupon::getTermBeginTime)
                        .or().le(UserCoupon::getTermBeginTime, now))
                .gt(UserCoupon::getTermEndTime, now)
                .orderByAsc(UserCoupon::getId)
                .last("LIMIT " + MAX_CANDIDATE_RECORDS));
        return records == null ? Collections.emptyList() : records;
    }

    private boolean hasUsableRule(Coupon coupon) {
        DiscountType type = coupon.getDiscountType();
        Integer value = coupon.getDiscountValue();
        if (type == null || value == null || value <= 0) {
            return false;
        }
        if (type == DiscountType.RATE_DISCOUNT && value >= 100) {
            return false;
        }
        int threshold = nz(coupon.getThresholdAmount());
        if ((type == DiscountType.PER_PRICE_DISCOUNT || type == DiscountType.PRICE_DISCOUNT) && threshold <= 0) {
            return false;
        }
        return true;
    }

    private boolean[] matchItems(boolean specific, List<CouponScope> scopes, List<OrderItem> items) {
        boolean[] matches = new boolean[items.size()];
        if (!specific) {
            Arrays.fill(matches, true);
            return matches;
        }
        Set<Long> courseScopeIds = new HashSet<>();
        Set<Long> categoryScopeIds = new HashSet<>();
        for (CouponScope scope : scopes) {
            if (scope.getType() == null || scope.getBizId() == null) {
                continue;
            }
            if (scope.getType() == SCOPE_TYPE_COURSE) {
                courseScopeIds.add(scope.getBizId());
            } else if (scope.getType() == SCOPE_TYPE_CATEGORY) {
                categoryScopeIds.add(scope.getBizId());
            }
        }
        if (courseScopeIds.isEmpty() && categoryScopeIds.isEmpty()) {
            return matches;
        }
        for (int position = 0; position < items.size(); position++) {
            OrderItem item = items.get(position);
            matches[position] = courseScopeIds.contains(item.getCourseId())
                    || !Collections.disjoint(categoryScopeIds, item.getCategoryIds());
        }
        return matches;
    }

    private Plan search(List<Candidate> candidates, List<OrderItem> items) {
        Plan best = new Plan(Collections.emptyList(), 0);
        int maxSize = Math.min(MAX_COUPONS_PER_PLAN, candidates.size());
        for (int size = 1; size <= maxSize; size++) {
            best = enumerate(candidates, items, new int[size], 0, 0, best);
        }
        return best;
    }

    private Plan enumerate(List<Candidate> candidates, List<OrderItem> items, int[] chosen,
                           int start, int depth, Plan best) {
        if (depth == chosen.length) {
            return bestPermutation(candidates, items, chosen, best);
        }
        int left = chosen.length - depth;
        for (int position = start; position <= candidates.size() - left; position++) {
            chosen[depth] = position;
            best = enumerate(candidates, items, chosen, position + 1, depth + 1, best);
        }
        return best;
    }

    private Plan bestPermutation(List<Candidate> candidates, List<OrderItem> items,
                                 int[] chosen, Plan best) {
        return permute(candidates, items, chosen.clone(), 0, best);
    }

    private Plan permute(List<Candidate> candidates, List<OrderItem> items,
                         int[] order, int index, Plan best) {
        if (index == order.length) {
            Plan plan = evaluate(candidates, order, items);
            return better(plan, best) ? plan : best;
        }
        for (int position = index; position < order.length; position++) {
            swap(order, index, position);
            best = permute(candidates, items, order, index + 1, best);
            swap(order, index, position);
        }
        return best;
    }

    private Plan evaluate(List<Candidate> candidates, int[] order, List<OrderItem> items) {
        int[] remaining = remaining(items);
        List<Usage> usages = new ArrayList<>(order.length);
        int totalDiscount = 0;
        for (int index : order) {
            Candidate candidate = candidates.get(index);
            int applicableAmount = applicableAmount(candidate.matches, remaining);
            int discount = calculateDiscount(candidate.coupon, applicableAmount);
            if (discount <= 0) {
                continue;
            }
            int allocated = allocate(candidate.matches, remaining, discount);
            if (allocated <= 0) {
                continue;
            }
            totalDiscount += allocated;
            usages.add(new Usage(candidate, applicableAmount, allocated,
                    matchedCourseIds(candidate.matches, items)));
        }
        return new Plan(usages, totalDiscount);
    }

    private int calculateDiscount(Coupon coupon, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int threshold = nz(coupon.getThresholdAmount());
        if (amount < threshold) {
            return 0;
        }
        int value = coupon.getDiscountValue() == null ? 0 : coupon.getDiscountValue();
        long raw;
        switch (coupon.getDiscountType()) {
            case PER_PRICE_DISCOUNT:
                if (threshold <= 0) {
                    return 0;
                }
                raw = (long) (amount / threshold) * value;
                break;
            case RATE_DISCOUNT:
                if (value <= 0 || value >= 100) {
                    return 0;
                }
                raw = (long) amount * (100 - value) / 100;
                break;
            case NO_THRESHOLD:
            case PRICE_DISCOUNT:
                raw = value;
                break;
            default:
                return 0;
        }
        int max = nz(coupon.getMaxDiscountAmount());
        if (max > 0) {
            raw = Math.min(raw, max);
        }
        raw = Math.min(raw, amount);
        return raw <= 0 ? 0 : (int) raw;
    }

    /**
     * 将抵扣额按剩余金额比例分摊到命中的课程，保证后续按范围抵扣时金额一致且不超扣。
     */
    private int allocate(boolean[] matches, int[] remaining, int discount) {
        int applicable = applicableAmount(matches, remaining);
        if (applicable <= 0 || discount <= 0) {
            return 0;
        }
        if (discount >= applicable) {
            for (int position = 0; position < remaining.length; position++) {
                if (matches[position]) {
                    remaining[position] = 0;
                }
            }
            return applicable;
        }

        int[] allocated = new int[remaining.length];
        long floorSum = 0;
        for (int position = 0; position < remaining.length; position++) {
            if (!matches[position]) {
                continue;
            }
            allocated[position] = (int) ((long) discount * remaining[position] / applicable);
            floorSum += allocated[position];
        }
        int left = discount - (int) floorSum;
        for (int position = 0; position < remaining.length && left > 0; position++) {
            if (!matches[position]) {
                continue;
            }
            int extra = Math.min(remaining[position] - allocated[position], left);
            allocated[position] += extra;
            left -= extra;
        }
        for (int position = 0; position < remaining.length; position++) {
            if (matches[position]) {
                remaining[position] -= allocated[position];
            }
        }
        return discount - left;
    }

    private int applicableAmount(boolean[] matches, int[] remaining) {
        int amount = 0;
        for (int position = 0; position < remaining.length; position++) {
            if (matches[position]) {
                amount += remaining[position];
            }
        }
        return amount;
    }

    private boolean hasMatch(boolean[] matches) {
        for (boolean match : matches) {
            if (match) {
                return true;
            }
        }
        return false;
    }

    private int[] remaining(List<OrderItem> items) {
        int[] remaining = new int[items.size()];
        for (int position = 0; position < items.size(); position++) {
            remaining[position] = items.get(position).getPrice();
        }
        return remaining;
    }

    private List<Long> matchedCourseIds(boolean[] matches, List<OrderItem> items) {
        List<Long> ids = new ArrayList<>();
        for (int position = 0; position < items.size(); position++) {
            if (matches[position]) {
                ids.add(items.get(position).getCourseId());
            }
        }
        return ids;
    }

    private boolean better(Plan candidate, Plan current) {
        if (candidate.totalDiscount != current.totalDiscount) {
            return candidate.totalDiscount > current.totalDiscount;
        }
        if (candidate.usages.size() != current.usages.size()) {
            return candidate.usages.size() < current.usages.size();
        }
        for (int position = 0; position < candidate.usages.size(); position++) {
            Long left = candidate.usages.get(position).candidate.userCoupon.getId();
            Long right = current.usages.get(position).candidate.userCoupon.getId();
            int compared = left.compareTo(right);
            if (compared != 0) {
                return compared < 0;
            }
        }
        return false;
    }

    private CouponRecommendationVO emptyResult(int originalAmount) {
        CouponRecommendationVO result = new CouponRecommendationVO();
        result.setOriginalAmount(originalAmount);
        result.setTotalDiscount(0);
        result.setPayableAmount(originalAmount);
        result.setUserCouponIds(Collections.emptyList());
        result.setCouponIds(Collections.emptyList());
        result.setUsages(Collections.emptyList());
        return result;
    }

    private CouponRecommendationVO toVO(int originalAmount, Plan plan) {
        CouponRecommendationVO result = new CouponRecommendationVO();
        result.setOriginalAmount(originalAmount);
        result.setTotalDiscount(plan.totalDiscount);
        result.setPayableAmount(Math.max(0, originalAmount - plan.totalDiscount));
        result.setUserCouponIds(plan.usages.stream()
                .map(usage -> usage.candidate.userCoupon.getId()).collect(Collectors.toList()));
        result.setCouponIds(plan.usages.stream()
                .map(usage -> usage.candidate.coupon.getId()).collect(Collectors.toList()));
        result.setUsages(plan.usages.stream().map(this::toUsageVO).collect(Collectors.toList()));
        return result;
    }

    private CouponUsageVO toUsageVO(Usage usage) {
        Coupon coupon = usage.candidate.coupon;
        CouponUsageVO vo = new CouponUsageVO();
        vo.setUserCouponId(usage.candidate.userCoupon.getId());
        vo.setCouponId(coupon.getId());
        vo.setCouponName(coupon.getName());
        vo.setDiscountType(coupon.getDiscountType());
        vo.setThresholdAmount(nz(coupon.getThresholdAmount()));
        vo.setDiscountValue(coupon.getDiscountValue());
        vo.setMaxDiscountAmount(nz(coupon.getMaxDiscountAmount()));
        vo.setSpecific(Boolean.TRUE.equals(coupon.getSpecific()));
        vo.setScopeBizIds(usage.candidate.scopes.stream()
                .map(CouponScope::getBizId).filter(Objects::nonNull).distinct().sorted()
                .collect(Collectors.toList()));
        vo.setMatchedCourseIds(usage.matchedCourseIds);
        vo.setApplicableAmount(usage.applicableAmount);
        vo.setDiscountAmount(usage.discountAmount);
        vo.setRuleDescription(describe(coupon, usage.discountAmount));
        return vo;
    }

    private String describe(Coupon coupon, int discount) {
        int threshold = nz(coupon.getThresholdAmount());
        int max = nz(coupon.getMaxDiscountAmount());
        StringBuilder description = new StringBuilder(coupon.getDiscountType().getDesc());
        if (coupon.getDiscountType() == DiscountType.RATE_DISCOUNT) {
            description.append("：满").append(yuan(threshold)).append("元享")
                    .append(BigDecimal.valueOf(coupon.getDiscountValue()).movePointLeft(1)
                            .stripTrailingZeros().toPlainString()).append("折");
        } else if (coupon.getDiscountType() == DiscountType.PER_PRICE_DISCOUNT) {
            description.append("：每满").append(yuan(threshold)).append("元减")
                    .append(yuan(coupon.getDiscountValue())).append("元");
        } else if (coupon.getDiscountType() == DiscountType.PRICE_DISCOUNT) {
            description.append("：满").append(yuan(threshold)).append("元减")
                    .append(yuan(coupon.getDiscountValue())).append("元");
        } else {
            description.append("：无门槛减").append(yuan(coupon.getDiscountValue())).append("元");
        }
        if (max > 0) {
            description.append("，最高减").append(yuan(max)).append("元");
        }
        description.append("；本次抵扣").append(yuan(discount)).append("元");
        return description.toString();
    }

    private static String yuan(int cents) {
        return (cents / 100) + "." + (cents % 100 < 10 ? "0" : "") + (cents % 100);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static void addIfNotNull(Set<Long> target, Long value) {
        if (value != null) {
            target.add(value);
        }
    }

    private static void swap(int[] values, int left, int right) {
        int temp = values[left];
        values[left] = values[right];
        values[right] = temp;
    }

    private static final class OrderItem {
        private final Long courseId;
        private final int price;
        private final Set<Long> categoryIds;

        private OrderItem(Long courseId, int price, Set<Long> categoryIds) {
            this.courseId = courseId;
            this.price = price;
            this.categoryIds = categoryIds;
        }

        private Long getCourseId() {
            return courseId;
        }

        private int getPrice() {
            return price;
        }

        private Set<Long> getCategoryIds() {
            return categoryIds;
        }
    }

    private static final class Candidate {
        private final UserCoupon userCoupon;
        private final Coupon coupon;
        private final List<CouponScope> scopes;
        private final boolean[] matches;
        private int initialDiscount;

        private Candidate(UserCoupon userCoupon, Coupon coupon, List<CouponScope> scopes,
                          boolean[] matches) {
            this.userCoupon = userCoupon;
            this.coupon = coupon;
            this.scopes = scopes;
            this.matches = matches;
        }
    }

    private static final class Usage {
        private final Candidate candidate;
        private final int applicableAmount;
        private final int discountAmount;
        private final List<Long> matchedCourseIds;

        private Usage(Candidate candidate, int applicableAmount, int discountAmount,
                      List<Long> matchedCourseIds) {
            this.candidate = candidate;
            this.applicableAmount = applicableAmount;
            this.discountAmount = discountAmount;
            this.matchedCourseIds = matchedCourseIds;
        }
    }

    private static final class Plan {
        private final List<Usage> usages;
        private final int totalDiscount;

        private Plan(List<Usage> usages, int totalDiscount) {
            this.usages = usages;
            this.totalDiscount = totalDiscount;
        }
    }
}
