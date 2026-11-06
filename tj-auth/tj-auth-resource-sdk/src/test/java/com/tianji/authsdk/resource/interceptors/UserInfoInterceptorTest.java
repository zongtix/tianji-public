package com.tianji.authsdk.resource.interceptors;

import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class UserInfoInterceptorTest {
    UserInfoInterceptor interceptor = new UserInfoInterceptor();
    @AfterEach void clear() { UserContext.removeUser(); }

    @Test void asyncStartReleasesIdentityBeforeServletThreadIsReused() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        request.addHeader("user-info", "42");
        interceptor.preHandle(request, response, new Object());
        assertThat(UserContext.getUser()).isEqualTo(42L);
        interceptor.afterConcurrentHandlingStarted(request, response, new Object());
        assertThat(UserContext.getUser()).isNull();
    }

    @Test void absentOrMalformedIdentityCannotInheritPriorThreadState() throws Exception {
        for (String identity : new String[]{null, "invalid"}) {
            UserContext.setUser(42L);
            var request = new MockHttpServletRequest();
            if (identity != null) { request.addHeader("user-info", identity); }
            interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
            assertThat(UserContext.getUser()).isNull();
        }
    }
}
