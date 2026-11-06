package com.tianji.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;


import com.tianji.api.dto.user.LoginFormDTO;
import com.tianji.auth.common.constants.JwtConstants;
import com.tianji.auth.service.IAccountService;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.WebUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 账户登录相关接口
 */
@RestController
@RequestMapping("/accounts")
@Tag(name = "账户管理")
@RequiredArgsConstructor
public class AccountController {

    private final IAccountService accountService;

    @Operation(summary = "登录并获取token")
    @PostMapping(value = "/login")
    public String loginByPw(@RequestBody LoginFormDTO loginFormDTO) {
        return accountService.login(loginFormDTO, false);
    }

    @Operation(summary = "管理端登录并获取token")
    @PostMapping(value = "/admin/login")
    public String adminLoginByPw(@RequestBody LoginFormDTO loginFormDTO) {
        return accountService.login(loginFormDTO, true);
    }

    @Operation(summary = "退出登录")
    @PostMapping(value = "/logout")
    public void logout() {
        accountService.logout();
    }

    @Operation(summary = "刷新token")
    @GetMapping(value = "/refresh")
    public String refreshToken(
            @CookieValue(value = JwtConstants.REFRESH_HEADER, required = false) String studentToken,
            @CookieValue(value = JwtConstants.ADMIN_REFRESH_HEADER, required = false) String adminToken
    ) {
        if (studentToken == null && adminToken == null) {
            throw new BadRequestException("登录超时");
        }
        String source = WebUtils.getHeader("origin");
        // 同域 /api 的 GET 通常没有 Origin，浏览器仍会携带 Referer。
        if (source == null) source = WebUtils.getHeader("referer");
        URI sourceUri;
        String host;
        try {
            sourceUri = source == null ? null : URI.create(source);
            host = sourceUri == null ? null : sourceUri.getHost();
        } catch (IllegalArgumentException invalidSource) {
            throw new BadRequestException("登录超时");
        }
        if (host == null) {
            throw new BadRequestException("登录超时");
        }
        // 本地学员站与管理站同用 localhost，必须同时区分端口，不能回退到另一端的 Cookie。
        boolean localStudent = "http".equalsIgnoreCase(sourceUri.getScheme()) && sourceUri.getPort() == 18082
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
        String token = host.startsWith("www.") || localStudent ? studentToken : adminToken;
        if (token == null) {
            throw new BadRequestException("登录超时");
        }
        return accountService.refreshToken(WebUtils.cookieBuilder().decode(token));
    }
}
