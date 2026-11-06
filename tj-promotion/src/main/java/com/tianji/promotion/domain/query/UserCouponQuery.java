package com.tianji.promotion.domain.query;

import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.validate.annotations.EnumValid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(description = "本人优惠券分页参数")
public class UserCouponQuery extends PageQuery {
    @NotNull(message = "优惠券状态不能为空")
    @EnumValid(enumeration = {1, 2, 3}, message = "优惠券状态不正确")
    @Schema(description = "状态：1未使用，2已使用，3已失效", defaultValue = "1")
    private Integer status = 1;

    @Override
    @NotNull(message = "页码不能为空")
    public Integer getPageNo() {
        return super.getPageNo();
    }

    @Override
    @NotNull(message = "每页查询数量不能为空")
    @Max(value = 1000, message = "每页查询数量不能超过1000")
    public Integer getPageSize() {
        return super.getPageSize();
    }
}
