package com.zhiguang.be.auth.controller;

import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.LogoutRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RefreshTokenRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.model.SendCodeRequest;
import com.zhiguang.be.auth.model.SendCodeResult;
import com.zhiguang.be.auth.service.AuthService;
import com.zhiguang.be.auth.service.VerificationCodeService;
import com.zhiguang.be.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口控制器。
 * 对外暴露注册、登录、刷新令牌、登出和密码重置等认证相关接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final VerificationCodeService verificationCodeService;

    /**
     * 构造认证控制器并注入认证服务。
     *
     * @param authService 认证领域服务
     * @param verificationCodeService 验证码服务
     */
    public AuthController(AuthService authService, VerificationCodeService verificationCodeService) {
        this.authService = authService;
        this.verificationCodeService = verificationCodeService;
    }

    /**
     * 发送验证码。
     * 支持注册、登录、重置密码三种场景。
     *
     * @param request 发送验证码请求体
     * @return 标准响应包装的发送结果
     */
    @PostMapping("/code/send")
    public ApiResponse<SendCodeResult> sendCode(@Valid @RequestBody SendCodeRequest request) {
        return ApiResponse.success(verificationCodeService.send(request.phone(), request.scene()));
    }

    /**
     * 处理用户注册请求。
     * 注册成功后会直接返回用户 ID 和首发的双令牌信息。
     *
     * @param request 注册请求体
     * @return 标准响应包装的会话信息
     */
    @PostMapping("/register")
    public ApiResponse<AuthSessionData> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    /**
     * 处理用户登录请求。
     * 登录流程会在服务层执行风控、验证码和密码校验。
     *
     * @param request 登录请求体
     * @return 标准响应包装的会话信息
     */
    @PostMapping("/login")
    public ApiResponse<AuthSessionData> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    /**
     * 使用刷新令牌换发新的令牌对。
     *
     * @param request 刷新令牌请求体
     * @return 标准响应包装的新令牌对
     */
    @PostMapping("/token/refresh")
    public ApiResponse<AuthTokens> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refreshToken(request.refreshToken()));
    }

    /**
     * 执行用户登出操作。
     * 支持只退出当前设备或退出全部设备。
     *
     * @param request 登出请求体
     * @return 标准响应包装的操作结果
     */
    @PostMapping("/logout")
    public ApiResponse<ActionResult> logout(@Valid @RequestBody LogoutRequest request) {
        return ApiResponse.success(authService.logout(request.refreshToken(), request.logoutScope()));
    }

    /**
     * 重置用户密码。
     * 重置成功后会清理已有刷新令牌，避免旧会话继续使用。
     *
     * @param request 重置密码请求体
     * @return 标准响应包装的操作结果
     */
    @PostMapping("/password/reset")
    public ApiResponse<ActionResult> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        return ApiResponse.success(authService.resetPassword(request));
    }
}