package com.tianji.remark.service;

import com.tianji.authsdk.resource.interceptors.LoginAuthInterceptor;
import com.tianji.authsdk.resource.interceptors.UserInfoInterceptor;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.service.impl.LikedRecordServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LikedRecordIdentityTest {
    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @Test
    void alternateHeadersCannotBecomeAnAuthenticatedUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("user-id", "20");
        request.addHeader("userId", "20");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new UserInfoInterceptor().preHandle(request, response, new Object());
        LikeCacheService cache = mock(LikeCacheService.class);

        assertFalse(new LoginAuthInterceptor().preHandle(request, response, new Object()));
        assertThrows(UnauthorizedException.class,
                () -> new LikedRecordServiceImpl(cache).saveLikeRecord(form()));
        verifyNoInteractions(cache);
    }

    @Test
    void serviceUsesTrustedContextForLikeAndUnlike() {
        LikeCacheService cache = mock(LikeCacheService.class);
        LikedRecordServiceImpl service = new LikedRecordServiceImpl(cache);
        UserContext.setUser(10L);
        LikeRecordFormDTO form = form();

        service.saveLikeRecord(form);
        form.setLiked(false);
        service.saveLikeRecord(form);

        verify(cache).change(10L, "QA", 2L, true);
        verify(cache).change(10L, "QA", 2L, false);
    }

    private LikeRecordFormDTO form() {
        LikeRecordFormDTO form = new LikeRecordFormDTO();
        form.setBizType("QA");
        form.setBizId(2L);
        form.setLiked(true);
        return form;
    }
}
