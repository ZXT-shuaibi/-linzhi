package com.zhiguang.be.auth.service;

import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RegisterRequest;

/**
 * 认证领域服务接口。
 * 定义注册、登录、刷新令牌、退出登录和重置密码等认证能力。
 */
public interface AuthService {

    /**
     * 注册新用户并创建登录会话。
     *
     * @param request 注册请求
     * @return 用户会话信息
     */
    AuthSessionData register(RegisterRequest request);

    /**
     * 校验登录请求并创建登录会话。
     *
     * @param request 登录请求
     * @return 用户会话信息
     */
    AuthSessionData login(LoginRequest request);

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
}