package com.tianji.promotion.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CouponClaimResultVO {
    private String requestId;
    private String status;
    private Long userCouponId;
    private String message;
}
