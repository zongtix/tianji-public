package com.tianji.auth.controller;

import com.tianji.auth.service.IAccountService;
import com.tianji.common.exceptions.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountRefreshTest {
    private final IAccountService accounts = mock(IAccountService.class);
    private final AccountController controller = new AccountController(accounts);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @AfterEach
    void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @ParameterizedTest
    @CsvSource({
            "origin,http://www.tianji.com,student",
            "origin,https://www.tianji.com,student",
            "referer,http://www.tianji.com/assistant,student",
            "referer,https://www.tianji.com/assistant,student",
            "referer,http://admin.tianji.com/,admin",
            "origin,http://localhost:18082,student",
            "referer,http://localhost:18082/#/details/index?id=1,student",
            "referer,http://127.0.0.1:18082/#/details/index?id=1,student",
            "origin,http://localhost:18083,admin"
    })
    void selectsTheCookieForTheBrowserSource(String header, String value, String expected) {
        request.addHeader(header, value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(accounts.refreshToken(expected)).thenReturn("refreshed");
        assertThat(controller.refreshToken("student", "admin")).isEqualTo("refreshed");
        verify(accounts).refreshToken(expected);
    }

    @Test
    void originTakesPrecedenceOverReferer() {
        request.addHeader("origin", "https://www.tianji.com");
        request.addHeader("referer", "https://admin.tianji.com/");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        controller.refreshToken("student", "admin");
        verify(accounts).refreshToken("student");
    }

    @Test
    void missingSourceDoesNotGuessBetweenStudentAndAdminCookies() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThatThrownBy(() -> controller.refreshToken("student", "admin")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(accounts);
    }

    @Test
    void missingStudentCookieDoesNotFallBackToAdmin() {
        request.addHeader("referer", "http://www.tianji.com/");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThatThrownBy(() -> controller.refreshToken(null, "admin")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(accounts);
    }

    @Test
    void localStudentOriginDoesNotUseAnAdminCookie() {
        request.addHeader("referer", "http://localhost:18082/#/details/index?id=1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThatThrownBy(() -> controller.refreshToken(null, "admin")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(accounts);
    }

    @Test
    void localAdminOriginDoesNotUseAStudentCookie() {
        request.addHeader("origin", "http://localhost:18083");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThatThrownBy(() -> controller.refreshToken("student", null)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(accounts);
    }
}
