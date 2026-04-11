package com.zhiguang.be.auth.service;

import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.AuthUserResponse;
import com.zhiguang.be.auth.model.ClientInfo;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.model.RegisterResult;
import com.zhiguang.be.auth.model.SendCodeRequest;
import com.zhiguang.be.auth.model.SendCodeResponse;

/**
 * 认证领域服务接口。
 * 定义验证码发送、注册、登录、刷新令牌、登出和密码重置等认证能力。
 */
public interface AuthService {

    /**
     * 发送开发态验证码。
     *
     * @param request 发送验证码请求
     * @return 发送结果
     */
    SendCodeResponse sendCode(SendCodeRequest request);

    /**
     * 注册新用户。
     * 注册成功后不自动登录，而是返回“需要去登录”的结果。
     *
     * @param request 注册请求
     * @return 注册结果
     */
    RegisterResult register(RegisterRequest request, ClientInfo clientInfo);

    /**
     * 校验登录请求并创建登录会话。
     *
     * @param request 登录请求
     * @return 用户会话信息
     */
    AuthSessionData login(LoginRequest request, ClientInfo clientInfo);

    /**
     * 使用刷新令牌换发一组新令牌。
     *
     * @param refreshToken 刷新令牌
     * @return 新的访问令牌和刷新令牌
     */
    AuthTokens refreshToken(String refreshToken);

    /**
     * 执行退出登录操作。
     *
     * @param refreshToken 当前会话的刷新令牌
     * @param logoutScope 退出范围
     * @return 退出结果
     */
    ActionResult logout(String refreshToken, String logoutScope);

    /**
     * 重置用户密码并清理历史会话。
     *
     * @param request 重置密码请求
     * @return 操作结果
     */
    ActionResult resetPassword(PasswordResetRequest request);

    /**
     * 查询当前登录用户信息。
     *
     * @param userId 当前登录用户 ID
     * @return 当前用户概要信息
     */
    AuthUserResponse me(String userId);
}
