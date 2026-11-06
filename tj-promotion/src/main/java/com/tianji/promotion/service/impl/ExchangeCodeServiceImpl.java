package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.tianji.promotion.constants.PromotionConstants.COUPON_CODE_SERIAL_KEY;

/**
 * 兑换码服务实现
 */
@Service
@RequiredArgsConstructor
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {
    private static final int BATCH_SIZE = 1000;

    private final StringRedisTemplate redisTemplate;

    @Override
    @Async("generateExchangeCodeExecutor")
    // CallerRunsPolicy可能在发放事务的afterCommit线程执行，也必须开启独立写库事务。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void asyncGenerateCode(Coupon coupon) {
        Integer totalNum = coupon.getTotalNum();
        if (totalNum == null || totalNum <= 0) {
            throw new IllegalArgumentException("兑换码生成数量必须大于0");
        }
        // 一次申请整个号段，Redis失败或返回异常范围时不生成任何兑换码。
        Long maxSerial = redisTemplate.opsForValue().increment(COUPON_CODE_SERIAL_KEY, totalNum.longValue());
        if (maxSerial == null || maxSerial < totalNum || maxSerial > Integer.MAX_VALUE) {
            throw new IllegalStateException("兑换码序列号分配失败或超出当前Integer主键范围");
        }
        long minSerial = maxSerial - totalNum + 1;
        LocalDateTime now = LocalDateTime.now();
        List<ExchangeCode> codes = new ArrayList<>(Math.min(totalNum, BATCH_SIZE));
        for (long serial = minSerial; serial <= maxSerial; serial++) {
            codes.add(new ExchangeCode()
                    .setId(Math.toIntExact(serial))
                    .setCode(CodeUtil.generateCode(serial, coupon.getId()))
                    .setStatus(ExchangeCodeStatus.UNUSED)
                    .setType(1)
                    .setExchangeTargetId(coupon.getId())
                    .setExpiredTime(coupon.getIssueEndTime())
                    .setCreateTime(now)
                    .setUpdateTime(now));
            if (codes.size() == BATCH_SIZE || serial == maxSerial) {
                if (!saveBatch(codes, BATCH_SIZE)) {
                    throw new IllegalStateException("兑换码批量保存失败");
                }
                codes.clear();
            }
        }
        // 申请过的号段不回退；失败时允许留下空洞，避免与并发任务重复使用序号。
    }
}
