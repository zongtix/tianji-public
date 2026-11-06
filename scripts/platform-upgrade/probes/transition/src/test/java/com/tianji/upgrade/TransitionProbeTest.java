package com.tianji.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class TransitionProbeTest {
    @Autowired Environment environment;
    @Autowired TestRestTemplate http;
    @Test void java17LombokAndWebWorkWithBootTwoFinalPatch() {
        assertEquals("java17-transition", http.getForObject("/probe", TransitionApplication.Payload.class).getValue());
    }
    @Test void existingCloudFamilyStillLoadsIsolatedNacosConfig() {
        assertEquals("isolated-framework-probe", environment.getProperty("upgrade.probe.marker"));
    }
}
