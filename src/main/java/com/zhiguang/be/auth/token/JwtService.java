package com.zhiguang.be.auth.token;

import com.zhiguang.be.auth.model.AuthTokens;

/**
 * 接口说明。
 */
public interface JwtService {

    AuthTokens issueTokens(String userId);

    RefreshTokenClaims verifyRefreshToken(String refreshToken);
}
