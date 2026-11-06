package com.tianji.media.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.domain.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "媒资搜索条件")
public class MediaQuery extends PageQuery {
    @Schema(description = "媒资名称关键字")
    private String name;
}
