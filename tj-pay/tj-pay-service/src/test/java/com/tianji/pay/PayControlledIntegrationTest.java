package com.tianji.pay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.pay.domain.po.PayOrder;
import com.tianji.pay.sdk.dto.PayResultDTO;
import com.tianji.pay.service.IPayOrderService;
import com.tianji.pay.third.model.PayStatus;
import com.wechat.pay.contrib.apache.httpclient.auth.Verifier;
import com.wechat.pay.contrib.apache.httpclient.cert.CertificatesManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SDK certificate lookup/verification and outbound MQ are controlled; HTTP, AES and DB are real. */
@EnabledIfEnvironmentVariable(named = "TJ_UPGRADE_CONTROLLED_SERVICES", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.profiles.active=upgrade-test",
        "spring.cloud.nacos.server-addr=127.0.0.1:8848",
        "spring.cloud.nacos.config.namespace=tianji-platform-upgrade-test-boot3",
        "spring.cloud.nacos.discovery.namespace=tianji-platform-upgrade-test-boot3",
        "spring.cloud.nacos.discovery.register-enabled=false", "seata.enabled=false",
        "tj.jdbc.database=tj_upgrade_pay", "tj.xxl-job.executor.port=0", "tj.xxl-job.admin.address=",
        "tj.pay.wx.mch-id=upgrade-fixture", "tj.pay.wx.api-v3-key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
@DirtiesContext
class PayControlledIntegrationTest {
    private static final long ID = 900099100L;
    private static final String KEY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @MockitoBean CertificatesManager certificates;
    @MockitoBean CloseableHttpClient wxHttp;
    @MockitoBean RabbitMqHelper messages;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired IPayOrderService orders;
    private Verifier verifier;

    @BeforeEach void prepare() throws Exception {
        assertEquals("tj_upgrade_pay", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE id=?", Integer.class, ID));
        verifier = mock(Verifier.class);
        when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(true);
        when(certificates.getVerifier("upgrade-fixture")).thenReturn(verifier);
        assertTrue(orders.save(new PayOrder().setId(ID).setBizOrderNo(ID).setPayOrderNo(ID)
                .setBizUserId(900000001L).setPayChannelCode("wxPay").setAmount(1000).setPayType(4)
                .setStatus(PayStatus.WAIT_BUYER_PAY.getValue()).setNotifyTimes(0).setNotifyStatus(0)
                .setPayOverTime(LocalDateTime.now().plusMinutes(30)).setExpandJson("").setNotifyUrl("")
                .setResultCode("").setResultMsg("").setQrCodeUrl("")));
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM pay_order WHERE id=?", ID);
    }

    @Test void decryptedCallbackUpdatesOnceAndDuplicateDoesNotPublishAgain() throws Exception {
        HttpEntity<String> callback = callback(1000);
        assertEquals(HttpStatus.OK, http.postForEntity("/notify/wxPay", callback, String.class).getStatusCode());
        assertEquals(PayStatus.TRADE_SUCCESS.getValue(), orders.getById(ID).getStatus());
        assertEquals(HttpStatus.OK, http.postForEntity("/notify/wxPay", callback, String.class).getStatusCode());
        verify(messages, times(1)).send(eq(MqConstants.Exchange.PAY_EXCHANGE), eq(MqConstants.Key.PAY_SUCCESS),
                argThat((PayResultDTO event) -> event.getBizOrderId().equals(ID) && event.getPayOrderNo().equals(ID)));
        verifyNoInteractions(wxHttp);
    }

    @Test void amountMismatchCannotMarkPaidOrPublish() throws Exception {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, http.postForEntity("/notify/wxPay", callback(999), String.class).getStatusCode());
        assertEquals(PayStatus.WAIT_BUYER_PAY.getValue(), orders.getById(ID).getStatus());
        verifyNoInteractions(messages, wxHttp);
    }

    @Test void rejectedSignatureCannotMarkPaidOrPublish() throws Exception {
        when(verifier.verify(anyString(), any(byte[].class), anyString())).thenReturn(false);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, http.postForEntity("/notify/wxPay", callback(1000), String.class).getStatusCode());
        assertEquals(PayStatus.WAIT_BUYER_PAY.getValue(), orders.getById(ID).getStatus());
        verifyNoInteractions(messages, wxHttp);
    }

    @Test void openApiServesFromActualApplication() throws Exception {
        assertTrue(json.readTree(http.getForObject("/v3/api-docs/default", String.class)).has("openapi"));
        verifyNoInteractions(messages, wxHttp);
    }

    private HttpEntity<String> callback(int amount) throws Exception {
        String nonce = "123456789012";
        String associated = "transaction";
        byte[] payload = json.writeValueAsBytes(Map.of("out_trade_no", String.valueOf(ID),
                "amount", Map.of("total", amount), "success_time", "2026-10-12T12:00:00+08:00"));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(payload));
        String body = json.writeValueAsString(Map.of("id", "UPGRADE_FIXTURE", "create_time", "2026-10-12T12:00:00+08:00",
                "event_type", "TRANSACTION.SUCCESS", "resource_type", "encrypt-resource", "summary", "controlled test",
                "resource", Map.of("algorithm", "AEAD_AES_256_GCM", "ciphertext", ciphertext,
                        "associated_data", associated, "nonce", nonce, "original_type", "transaction")));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Wechatpay-Serial", "UPGRADE_FIXTURE");
        headers.set("Wechatpay-Nonce", nonce);
        headers.set("Wechatpay-Timestamp", String.valueOf(Instant.now().getEpochSecond()));
        headers.set("Wechatpay-Signature", "fixture-signature");
        return new HttpEntity<>(body, headers);
    }
}
