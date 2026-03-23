package com.zhiguang.be.auth.model;

import java.time.Instant;

/**
 * 数据结构说明。
 */
public record AuthTokens(
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt,
        String tokenType
) {
}

