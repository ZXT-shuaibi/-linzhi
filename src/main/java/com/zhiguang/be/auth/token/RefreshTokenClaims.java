package com.zhiguang.be.auth.token;

import java.time.Instant;

/**
 * 刷新令牌解析结果。
 */
public record RefreshTokenClaims(
        String userId,
        String role,
        String jti,
        Instant expiresAt
) {
}

