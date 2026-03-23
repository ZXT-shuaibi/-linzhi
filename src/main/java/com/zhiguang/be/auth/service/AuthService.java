package com.zhiguang.be.auth.service;

import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RegisterRequest;

/**
 * 认证领域服务接口。
 * 定义认证模块对外提供的核心业务能力，包括注册、登录、令牌刷新、登出与密码重置。
 */
public interface AuthService {

    /**
     * 注册新用户并创建登录会话。
     *
     * @param request 注册请求，包含手机号、密码、昵称等必要信息
     * @return 注册完成后的会话数据（用户 ID 与双令牌）
     */
    AuthSessionData register(RegisterRequest request);

    /**
     * 用户登录并创建登录会话。
     *
     * @param request 登录请求，包含账号标识与密码等信息
     * @return 登录成功后的会话数据（用户 ID 与双令牌）
     */
    AuthSessionData login(LoginRequest request);

    /**
     * 使用刷新令牌获取新的令牌对。
     *
     * @param refreshToken 客户端提交的刷新令牌
     * @return 新签发的访问令牌与刷新令牌
     */
    AuthTokens refreshToken(String refreshToken);

    /**
     * 执行登出操作并撤销会话。
     *
     * @param refreshToken 当前会话对应的刷新令牌
     * @param logoutScope 登出范围（当前设备或全部设备）
     * @return 登出结果
     */
    ActionResult logout(String refreshToken, String logoutScope);

    /**
     * 重置用户密码并清理会话。
     *
     * @param request 重置密码请求，包含手机号与新密码信息
     * @return 密码重置结果
     */
    ActionResult resetPassword(PasswordResetRequest request);
}
