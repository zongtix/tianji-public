package com.tianji.promotion.service.claim;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.CouponClaimRequest;
import com.tianji.promotion.domain.vo.CouponClaimResultVO;
import com.tianji.promotion.mapper.CouponClaimRequestMapper;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.promotion.async", name = "enabled", havingValue = "true")
public class CouponClaimService {
    private final CouponClaimRequestMapper requestMapper;

    public CouponClaimResultVO receive(Long couponId, String idempotencyKey) {
        if (couponId == null || couponId <= 0) {
            throw new BadRequestException("无效优惠券ID");
        }
        return submit("RECEIVE", couponId, null, idempotencyKey);
    }

    public CouponClaimResultVO exchange(String code, String idempotencyKey) {
        long serial = CodeUtil.parseCode(code);
        if (serial <= 0 || serial > Integer.MAX_VALUE) {
            throw new BadRequestException("无效兑换码");
        }
        return submit("EXCHANGE", null, code, idempotencyKey);
    }

    public CouponClaimResultVO get(String requestId) {
        CouponClaimRequest request = requestMapper.selectOne(Wrappers.<CouponClaimRequest>lambdaQuery()
                .eq(CouponClaimRequest::getId, requestId).eq(CouponClaimRequest::getUserId, requireUser()));
        if (request == null) {
            throw new BadRequestException("领取请求不存在");
        }
        return result(request);
    }

    private CouponClaimResultVO submit(String operation, Long couponId, String code, String key) {
        Long userId = requireUser();
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BadRequestException("Idempotency-Key需为8至64位字母、数字、下划线或短横线");
        }
        CouponClaimRequest existing = findByKey(userId, key);
        if (existing != null) {
            return sameResult(existing, operation, couponId, code);
        }
        LocalDateTime now = LocalDateTime.now();
        CouponClaimRequest request = new CouponClaimRequest().setId(UUID.randomUUID().toString())
                .setUserId(userId).setIdempotencyKey(key).setOperation(operation).setCouponId(couponId)
                .setExchangeCode(code).setStatus("PENDING").setAttempts(0).setMessage("请求已受理，等待处理")
                .setNextProcessTime(now).setNextPublishTime(now).setCreateTime(now).setUpdateTime(now);
        try {
            requestMapper.insert(request);
        } catch (DuplicateKeyException duplicate) {
            existing = findByKey(userId, key);
            if (existing == null) {
                throw duplicate;
            }
            return sameResult(existing, operation, couponId, code);
        }
        return result(request);
    }

    private CouponClaimRequest findByKey(Long userId, String key) {
        return requestMapper.selectOne(Wrappers.<CouponClaimRequest>lambdaQuery()
                .eq(CouponClaimRequest::getUserId, userId).eq(CouponClaimRequest::getIdempotencyKey, key));
    }

    private CouponClaimResultVO sameResult(CouponClaimRequest request, String operation, Long couponId, String code) {
        if (!operation.equals(request.getOperation()) || !Objects.equals(couponId, request.getCouponId())
                || !Objects.equals(code, request.getExchangeCode())) {
            throw new BadRequestException("同一Idempotency-Key不能用于不同领取请求");
        }
        return result(request);
    }

    private Long requireUser() {
        Long userId = UserContext.getUser();
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("请先登录后再领取优惠券");
        }
        return userId;
    }

    private CouponClaimResultVO result(CouponClaimRequest request) {
        return new CouponClaimResultVO().setRequestId(request.getId()).setStatus(request.getStatus())
                .setUserCouponId(request.getUserCouponId()).setMessage(request.getMessage());
    }
}
