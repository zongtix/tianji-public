package com.tianji.message.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "短信发送参数")
public class SmsInfoDTO {
    @Schema(description = "模板代号")
    private String templateCode;
    @Schema(description = "手机号码")
    private Iterable<String> phones;
    @Schema(description = "模板参数")
    private Map<String, String> templateParams;
}
