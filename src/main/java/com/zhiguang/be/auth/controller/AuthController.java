package com.zhiguang.be.auth.controller;

import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.LogoutRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RefreshTokenRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.service.AuthService;
import com.zhiguang.be.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口控制器。
 * 负责处理注册、登录、令牌刷新、登出和密码重置等认证相关请求。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 构造函数：注入认证服务。
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户注册。
     * 作用：创建新用户并签发 Access/Refresh 双令牌，返回登录态会话信息。
     */
    @PostMapping("/register")
    public ApiResponse<AuthSessionData> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    /**
     * 用户登录。
     * 作用：校验账号凭证并签发 Access/Refresh 双令牌，返回登录态会话信息。
     */
    @PostMapping("/login")
    public ApiResponse<AuthSessionData> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    /**
     * 刷新访问令牌。
     * 作用：使用 Refresh Token 完成令牌轮换，返回新的 Access/Refresh 令牌对。
     */
    @PostMapping("/token/refresh")
    public ApiResponse<AuthTokens> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refreshToken(request.refreshToken()));
    }

    /**
     * 用户登出。
     * 作用：撤销指定范围内的 Refresh Token（当前设备或全部设备），使会话失效。
     */
    @PostMapping("/logout")
    public ApiResponse<ActionResult> logout(@Valid @RequestBody LogoutRequest request) {
        return ApiResponse.success(authService.logout(request.refreshToken(), request.logoutScope()));
    }

    /**
     * 重置密码。
     * 作用：校验重置请求并更新用户密码，返回重置处理结果。
     */
    @PostMapping("/password/reset")
    public ApiResponse<ActionResult> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        return ApiResponse.success(authService.resetPassword(request));
    }
}
