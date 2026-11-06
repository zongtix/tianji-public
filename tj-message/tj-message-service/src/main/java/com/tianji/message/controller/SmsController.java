package com.tianji.message.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.api.dto.sms.SmsInfoDTO;
import com.tianji.message.service.ISmsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "短信发送控制器")
@RestController
@RequestMapping("sms")
@RequiredArgsConstructor
public class SmsController {

    private final ISmsService smsService;

    @Operation(summary = "同步发送短信")
    @PostMapping("message")
    public void sendMessage(@RequestBody SmsInfoDTO smsInfoDTO){
        smsService.sendMessageAsync(smsInfoDTO);
    }
}
