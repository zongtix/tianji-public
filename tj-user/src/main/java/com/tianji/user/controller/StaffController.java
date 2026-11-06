package com.tianji.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.user.domain.query.UserPageQuery;
import com.tianji.user.domain.vo.StaffVO;
import com.tianji.user.service.IStaffService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 员工详情表 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2022-07-12
 */
@RestController
@RequestMapping("/staffs")
@Tag(name = "用户管理接口")
public class StaffController {

    private final IStaffService staffService;

    public StaffController(IStaffService staffService) {
        this.staffService = staffService;
    }

    @Operation(summary = "分页查询员工信息")
    @GetMapping("page")
    public PageDTO<StaffVO> queryStaffPage(UserPageQuery pageQuery){
        return staffService.queryStaffPage(pageQuery);
    }
}
