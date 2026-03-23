package com.zhiguang.be.auth.token;

import java.time.Instant;

/**
 * 数据结构说明。
 */
public record RefreshTokenClaims(
        String userId,
        String jti,
        Instant expiresAt
) {
}

