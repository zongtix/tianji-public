package com.tianji.trade.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AlipaySandboxMcpTest {
    private final AlipaySandboxMcp client = new AlipaySandboxMcp(settings(), new ObjectMapper());

    static SandboxCheckoutProperties settings() {
        var settings = new SandboxCheckoutProperties();
        settings.setServerScript(new java.io.File("target/fixture.js").getAbsolutePath());
        settings.setAppId("fixture-app");
        settings.setAppKey("fixture-key");
        settings.setAlipayPublicKey("fixture-public");
        return settings;
    }

    private String url(String host, String order, String amount) {
        String business = URLEncoder.encode("{\"out_trade_no\":\"" + order + "\",\"total_amount\":\"" + amount + "\"}", StandardCharsets.UTF_8);
        return "网页支付链接: [点击完成支付](https://" + host
                + "/gateway.do?app_id=fixture-app&method=alipay.trade.page.pay&sign_type=RSA2&sign=fixture-sign&biz_content=" + business + ")";
    }

    @Test void acceptsOnlyExpectedSandboxOrderAndAmount() {
        String raw = url("openapi-sandbox.dl.alipaydev.com", "123", "149.00");
        assertTrue(client.paymentUrl(raw, "123", 14900).startsWith("https://openapi-sandbox.dl.alipaydev.com/"));
        assertEquals(new java.math.BigDecimal("149.00"), AlipaySandboxMcp.money(14900));
        assertThrows(Exception.class, () -> client.paymentUrl(url("openapi.alipay.com", "123", "149.00"), "123", 14900));
        assertThrows(Exception.class, () -> client.paymentUrl(raw, "456", 14900));
        assertThrows(Exception.class, () -> client.paymentUrl(raw, "123", 14901));
    }

    @Test void rejectsDuplicateParametersWrongAppAndMalformedLinks() {
        String raw = url("openapi.alipaydev.com", "123", "149.00");
        assertThrows(Exception.class, () -> client.paymentUrl(raw.replace("app_id=fixture-app", "app_id=other"), "123", 14900));
        assertThrows(Exception.class, () -> client.paymentUrl(raw.replace("&method=", "&app_id=fixture-app&method="), "123", 14900));
        assertThrows(Exception.class, () -> client.paymentUrl("[支付](https://evil.example)", "123", 14900));
    }

    @Test void validatesProviderAmountAndKnownStatus() {
        assertEquals("TRADE_SUCCESS", client.paymentState("交易状态: TRADE_SUCCESS , 交易金额: 149.00 , 支付宝交易号: 12345", 14900).status());
        assertEquals("WAIT_BUYER_PAY", client.paymentState("交易状态: WAIT_BUYER_PAY , 交易金额: 149.00 , 支付宝交易号: 12345", 14900).status());
        assertThrows(Exception.class, () -> client.paymentState("交易状态: TRADE_SUCCESS , 交易金额: 1.00 , 支付宝交易号: 12345", 14900));
        assertThrows(Exception.class, () -> client.paymentState("交易状态: UNKNOWN , 交易金额: 149.00 , 支付宝交易号: 12345", 14900));
    }

    @Test void distinguishesAbsentTradeFromPermissionOrAuthenticationErrors() {
        assertEquals("NOT_CREATED", client.paymentState("交易查询失败. 查询结果: {\"code\":\"40004\",\"subCode\":\"ACQ.TRADE_NOT_EXIST\"}", 14900).status());
        assertThrows(Exception.class, () -> client.paymentState("交易查询失败. 查询结果: {\"code\":\"40002\",\"subCode\":\"isv.invalid-signature\"}", 14900));
    }
}
