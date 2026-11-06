package com.tianji.trade.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.domain.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "订单分页查询条件")
public class OrderPageQuery extends PageQuery {
    @Schema(description = "订单状态")
    private Integer status;
}
