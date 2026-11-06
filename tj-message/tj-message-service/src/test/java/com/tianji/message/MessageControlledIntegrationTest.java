package com.tianji.message;

import com.aliyun.sdk.service.dysmsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dysmsapi20170525.models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.api.dto.sms.SmsInfoDTO;
import com.tianji.message.domain.po.MessageTemplate;
import com.tianji.message.thirdparty.ali.AliSmsHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "TJ_UPGRADE_CONTROLLED_SERVICES", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.profiles.active=upgrade-test",
        "spring.cloud.nacos.server-addr=127.0.0.1:8848",
        "spring.cloud.nacos.config.namespace=tianji-platform-upgrade-test-boot3",
        "spring.cloud.nacos.discovery.namespace=tianji-platform-upgrade-test-boot3",
        "spring.cloud.nacos.discovery.register-enabled=false", "seata.enabled=false",
        "tj.jdbc.database=tj_upgrade_message", "tj.xxl-job.executor.port=0", "tj.xxl-job.admin.address=",
        "tj.sms.ali.access-id=upgrade-test-disabled", "tj.sms.ali.access-secret=upgrade-test-disabled"})
@DirtiesContext
class MessageControlledIntegrationTest {
    @MockitoBean AsyncClient sms;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AliSmsHandler handler;

    @BeforeEach void assertIsolation() {
        assertEquals("tj_upgrade_message", jdbc.queryForObject("SELECT DATABASE()", String.class));
    }

    @Test void privateMessageHttpPersistsAndIsolatesRecipient() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/inboxes", String.class).getStatusCode());
        String content = "Upgrade fixture " + UUID.randomUUID();
        try {
            var response = http.exchange("/inboxes", HttpMethod.POST, request("900000002",
                    Map.of("userId", 900000001L, "content", content)), String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(json.readTree(response.getBody()).asLong(-1) > 0, response.getBody());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_inbox WHERE user_id=900000001 AND publisher=900000002 AND content=?",
                    Integer.class, content));
            var recipient = http.exchange("/inboxes?pageSize=100", HttpMethod.GET, request("900000001", null), String.class);
            assertTrue(recipient.getBody().contains(content));
            var sender = http.exchange("/inboxes?pageSize=100", HttpMethod.GET, request("900000002", null), String.class);
            assertFalse(sender.getBody().contains(content));
        } finally {
            jdbc.update("DELETE FROM user_inbox WHERE content=? AND publisher=900000002", content);
        }
        verifyNoInteractions(sms);
    }

    @Test void smsSdkContractMapsTemplateAndPhoneWithoutSending() {
        SendSmsResponse response = mock(SendSmsResponse.class);
        SendSmsResponseBody body = mock(SendSmsResponseBody.class);
        when(body.getCode()).thenReturn("OK");
        when(response.getBody()).thenReturn(body);
        when(sms.sendSms(any())).thenReturn(CompletableFuture.completedFuture(response));
        SmsInfoDTO info = new SmsInfoDTO();
        info.setPhones(List.of("19900000001"));
        info.setTemplateParams(Map.of("code", "123456"));
        MessageTemplate template = new MessageTemplate();
        template.setThirdTemplateCode("UPGRADE_FIXTURE");
        template.setSignName("Upgrade Fixture");
        handler.send(info, template);
        verify(sms).sendSms(argThat(request -> "19900000001".equals(request.getPhoneNumbers())
                && "UPGRADE_FIXTURE".equals(request.getTemplateCode()) && request.getTemplateParam().contains("123456")));
    }

    @Test void openApiServesFromActualApplication() throws Exception {
        assertTrue(json.readTree(http.getForObject("/v3/api-docs/default", String.class)).has("openapi"));
        verifyNoInteractions(sms);
    }

    private HttpEntity<?> request(String user, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("user-info", user);
        return new HttpEntity<>(body, headers);
    }
}
