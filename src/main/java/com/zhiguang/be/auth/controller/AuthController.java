package com.zhiguang.be.auth.controller;

import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.AuthUserResponse;
import com.zhiguang.be.auth.model.ClientInfo;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.LogoutRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RefreshTokenRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.model.RegisterResult;
import com.zhiguang.be.auth.model.SendCodeRequest;
import com.zhiguang.be.auth.model.SendCodeResponse;
import com.zhiguang.be.auth.security.JwtSubjects;
import com.zhiguang.be.auth.service.AuthService;
import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ClientIpResolver clientIpResolver;

    /**
     * 构造认证控制器并注入认证服务。
     *
     * @param authService 认证领域服务
     */
    public AuthController(AuthService authService, ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 发送开发态验证码。
     * 当前实现不会调用真实短信网关，而是把验证码写入 Redis 并回传给前端联调。
     *
     * @param request 发送验证码请求体
     * @return 标准响应包装后的发送结果
     */
    @PostMapping("/send-code")
    public ApiResponse<SendCodeResponse> sendCode(@Valid @RequestBody SendCodeRequest request) {
        return ApiResponse.success(authService.sendCode(request));
    }

    /**
     * 处理用户注册请求。
     * 注册成功后仅返回注册结果，不会自动签发登录令牌。
     *
     * @param request 注册请求体
     * @return 标准响应包装后的注册结果
     */
    @PostMapping("/register")
    public ApiResponse<RegisterResult> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.register(request, resolveClient(httpRequest)));
    }

    /**
     * 处理用户登录请求。
     * 登录流程会在服务层执行黑名单、验证码挑战和密码校验。
     *
     * @param request 登录请求体
     * @return 标准响应包装后的会话信息
     */
    @PostMapping("/login")
    public ApiResponse<AuthSessionData> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.login(request, resolveClient(httpRequest)));
    }

    /**
     * 使用刷新令牌换发新的令牌对。
     *
     * @param request 刷新令牌请求体
     * @return 标准响应包装后的新令牌对
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
     * @return 标准响应包装后的操作结果
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
     * @return 标准响应包装后的操作结果
     */
    @PostMapping("/password/reset")
    public ApiResponse<ActionResult> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        return ApiResponse.success(authService.resetPassword(request));
    }

    /**
     * 查询当前登录用户信息。
     * 通过 Spring Security 注入的访问令牌读取用户 ID，并返回认证域用户概要信息。
     *
     * @param jwt 当前访问令牌
     * @return 标准响应包装后的用户信息
     */
    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(authService.me(JwtSubjects.requireSubject(jwt)));
    }

    /**
     * 从请求中提取客户端信息。
     *
     * @param request HTTP 请求对象
     * @return 客户端信息
     */
    private ClientInfo resolveClient(HttpServletRequest request) {
        return new ClientInfo(clientIpResolver.resolve(request), request.getHeader("User-Agent"));
    }
}
