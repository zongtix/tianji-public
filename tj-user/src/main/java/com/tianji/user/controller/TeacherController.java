package com.tianji.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.user.domain.query.UserPageQuery;
import com.tianji.user.domain.vo.TeacherPageVO;
import com.tianji.user.service.ITeacherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 教师详情表 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2022-07-12
 */
@RestController
@RequestMapping("/teachers")
@Tag(name = "用户管理接口")
public class TeacherController {

    @Autowired
    private ITeacherService teacherService;

    @GetMapping("/page")
    @Operation(summary = "分页查询教师信息")
    public PageDTO<TeacherPageVO> queryTeacherPage(UserPageQuery pageQuery){
        return teacherService.queryTeacherPage(pageQuery);
    }
}
