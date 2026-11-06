package com.tianji.user.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.domain.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "教师分页查询条件")
public class UserPageQuery extends PageQuery {
    @Schema(description = "账户状态")
    private Integer status;
    @Schema(description = "教师名称")
    private String name;
    @Schema(description = "手机号码")
    private String phone;
}
