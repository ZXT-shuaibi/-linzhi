package com.zhiguang.be.auth.token;

import java.time.Instant;

/**
 * 接口说明。
 */
public interface RefreshTokenStore {

    void save(String userId, String jti, Instant expiresAt);

    boolean isValid(String userId, String jti);

    void remove(String userId, String jti);

    void removeAll(String userId);
}

