package com.tianji.common.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CookieBuilderCompatibilityTest {
    @Test
    void debugLogDoesNotExposeCookieValue() {
        Logger logger = (Logger) LoggerFactory.getLogger(CookieBuilder.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        String syntheticValue = "synthetic-token/+ 值";
        String encodedValue = URLEncoder.encode(syntheticValue, StandardCharsets.UTF_8);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName("www.tianji.test");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new CookieBuilder(request, response).name("refresh").value(syntheticValue)
                    .maxAge(1800).httpOnly(true).build();

            assertThat(response.getCookie("refresh")).isNotNull();
            assertThat(response.getCookie("refresh").getValue()).isEqualTo(encodedValue);
            assertThat(response.getCookie("refresh").isHttpOnly()).isTrue();
            assertThat(capture.list).isNotEmpty();
            assertThat(capture.list).extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).doesNotContain(syntheticValue, encodedValue));
        } finally {
            logger.detachAppender(capture);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            capture.stop();
        }
    }

    @Test
    void inferredParentDomainIsAcceptedByTomcat10() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("www.tianji.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new CookieBuilder(request, response).name("refresh").value("test-value")
                .maxAge(1800).httpOnly(true).build();
        String header = new Rfc6265CookieProcessor().generateHeader(response.getCookie("refresh"), request);
        assertThat(header).contains("Domain=tianji.test", "Path=/", "HttpOnly", "Max-Age=1800");
    }

    @ParameterizedTest
    @ValueSource(strings = {".tianji.test", "tianji.test"})
    void explicitDomainAndLogoutUseSameCookieScope(String domain) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new CookieBuilder(request, response).name("refresh").value("").domain(domain)
                .maxAge(0).httpOnly(true).build();
        String header = new Rfc6265CookieProcessor().generateHeader(response.getCookie("refresh"), request);
        assertThat(header).contains("Domain=tianji.test", "Path=/", "HttpOnly", "Max-Age=0");
    }
}
