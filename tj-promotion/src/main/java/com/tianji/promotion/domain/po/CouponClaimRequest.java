package com.tianji.promotion.domain.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("coupon_claim_request")
public class CouponClaimRequest {
    @TableId
    private String id;
    private Long userId;
    private String idempotencyKey;
    private String operation;
    private Long couponId;
    private String exchangeCode;
    private String status;
    private Long userCouponId;
    private String message;
    private Integer attempts;
    private LocalDateTime nextProcessTime;
    private LocalDateTime nextPublishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
