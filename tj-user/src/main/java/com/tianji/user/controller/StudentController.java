package com.tianji.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.user.domain.dto.StudentFormDTO;
import com.tianji.user.domain.query.UserPageQuery;
import com.tianji.user.domain.vo.StudentPageVo;
import com.tianji.user.service.IStudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 学员详情表 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2022-07-12
 */
@RestController
@RequestMapping("/students")
@Tag(name = "用户管理接口")
public class StudentController {

    @Autowired
    private IStudentService studentService;

    @Operation(summary = "分页查询学生信息")
    @GetMapping("/page")
    public PageDTO<StudentPageVo> queryStudentPage(UserPageQuery pageQuery){
        return studentService.queryStudentPage(pageQuery);
    }

    @Operation(summary = "学员注册")
    @PostMapping("/register")
    public void registerStudent(@RequestBody StudentFormDTO studentFormDTO) {
        studentService.saveStudent(studentFormDTO);
    }

    @Operation(summary = "修改学员密码")
    @PutMapping("/password")
    public void updateMyPassword(@RequestBody StudentFormDTO studentFormDTO) {
        studentService.updateMyPassword(studentFormDTO);
    }
}
