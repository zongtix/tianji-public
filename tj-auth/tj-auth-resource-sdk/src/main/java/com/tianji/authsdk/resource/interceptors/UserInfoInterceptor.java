package com.tianji.authsdk.resource.interceptors;

import com.tianji.auth.common.constants.JwtConstants;
import com.tianji.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Slf4j
public class UserInfoInterceptor implements AsyncHandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserContext.removeUser();
        // 1.尝试获取头信息中的用户信息
        String authorization = request.getHeader(JwtConstants.USER_HEADER);
        // 2.判断是否为空
        if (authorization == null) {
            return true;
        }
        // 3.转为用户id并保存
        try {
            Long userId = Long.valueOf(authorization);
            UserContext.setUser(userId);
            return true;
        } catch (NumberFormatException e) {
            log.error("用户身份信息格式不正确，{}, 原因：{}", authorization, e.getMessage());
            return true;
        }
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // SSE releases the initial servlet thread before afterCompletion runs on async dispatch.
        UserContext.removeUser();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理用户信息
        UserContext.removeUser();
    }
}
