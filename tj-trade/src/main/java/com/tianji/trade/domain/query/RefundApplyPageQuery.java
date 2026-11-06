package com.tianji.trade.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.utils.DateUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "退款申请分页参数")
public class RefundApplyPageQuery extends PageQuery {

    @Schema(description = "退款id")
    private Long id;
    @Schema(description = "退款状态，1：待审批，2：取消退款，3：同意退款，4：拒绝退款，5：退款成功，6：退款失败")
    private Integer refundStatus;
    @Schema(description = "订单明细id")
    private Long orderDetailId;
    @Schema(description = "订单id")
    private Long orderId;
    @Schema(description = "学员手机号")
    private String mobile;
    @Schema(description = "申请开始时间")
    @DateTimeFormat(pattern = DateUtils.DEFAULT_DATE_TIME_FORMAT)
    private LocalDateTime applyStartTime;
    @Schema(description = "申请结束时间")
    @DateTimeFormat(pattern = DateUtils.DEFAULT_DATE_TIME_FORMAT)
    private LocalDateTime applyEndTime;
}
