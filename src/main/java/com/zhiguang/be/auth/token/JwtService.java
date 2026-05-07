package com.zhiguang.be.auth.token;

import com.zhiguang.be.auth.model.AuthTokens;

/**
 * JWT 服务接口。
 * 用于签发访问令牌和刷新令牌，并解析刷新令牌声明。
 */
public interface JwtService {

    /**
     * 为指定用户签发一组令牌。
     *
     * @param userId 用户 ID
     * @param role 角色
     * @return 访问令牌与刷新令牌
     */
    AuthTokens issueTokens(String userId, String role);

    /**
     * 校验并解析刷新令牌。
     *
     * @param refreshToken 刷新令牌字符串
     * @return 解析得到的刷新令牌声明
     */
    RefreshTokenClaims verifyRefreshToken(String refreshToken);
}