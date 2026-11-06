package com.tianji.promotion.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.validate.annotations.EnumValid;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotNull;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "优惠券查询参数")
@Accessors(chain = true)
public class CouponQuery extends PageQuery {

    @Schema(description = "优惠券折扣类型：1：每满减，2：折扣，3：无门槛，4：满减")
    @EnumValid(enumeration = {1, 2, 3, 4}, message = "优惠券折扣类型不正确")
    private Integer type;

    @Schema(description = "优惠券状态，1：待发放，2：未开始，3：发放中，4：发放结束，5：暂停")
    @EnumValid(enumeration = {1, 2, 3, 4, 5}, message = "优惠券状态不正确")
    private Integer status;

    @Schema(description = "优惠券名称")
    private String name;

    @Override
    @NotNull(message = "页码不能为空")
    public Integer getPageNo() {
        return super.getPageNo();
    }

    @Override
    @NotNull(message = "每页查询数量不能为空")
    public Integer getPageSize() {
        return super.getPageSize();
    }
}
