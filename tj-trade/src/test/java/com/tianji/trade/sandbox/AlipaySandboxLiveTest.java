package com.tianji.trade.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "TJ_ALIPAY_MCP_LIVE", matches = "true")
class AlipaySandboxLiveTest {
    @Test void javaSdkCanQueryAnAbsentSandboxTradeWithoutCreatingPayment() {
        var settings = new SandboxCheckoutProperties();
        settings.setServerScript(System.getenv("ALIPAY_MCP_SCRIPT"));
        settings.setNodeCommand(System.getenv("ALIPAY_MCP_NODE"));
        settings.setAppId(System.getenv("AP_APP_ID"));
        settings.setAppKey(System.getenv("AP_APP_KEY"));
        settings.setAlipayPublicKey(System.getenv("AP_PUB_KEY"));
        var client = new AlipaySandboxMcp(settings, new ObjectMapper());
        assertEquals("NOT_CREATED", client.query("TJMCPCHECK20261027JAVAONLY01", 14900).status());
    }
}
