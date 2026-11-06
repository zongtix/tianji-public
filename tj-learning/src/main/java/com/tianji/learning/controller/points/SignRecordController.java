package com.tianji.learning.controller.points;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.service.points.ISignRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("sign-records")
@RequiredArgsConstructor
@Tag(name = "签到记录相关接口")
public class SignRecordController {
    private final ISignRecordService signRecordService;

    @PostMapping
    @Operation(summary = "签到功能接口")
    public SignResultVO addSignRecords(){
        return signRecordService.addSignRecords();
    }

    @GetMapping
    @Operation(summary = "查询签到记录接口")
    public SignRecordVO querySignRecords(){
        return signRecordService.querySignRecords();
    }

}
