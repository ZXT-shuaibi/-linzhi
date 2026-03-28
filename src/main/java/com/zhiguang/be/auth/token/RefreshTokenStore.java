package com.zhiguang.be.auth.token;

import java.time.Instant;

/**
 * 刷新令牌白名单存储接口。
 * 用于保存、消费和撤销刷新令牌，防止重放和实现多端退出。
 */
public interface RefreshTokenStore {

    /**
     * 保存刷新令牌白名单记录。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @param expiresAt 令牌过期时间
     */
    void save(String userId, String jti, Instant expiresAt);

    /**
     * 判断刷新令牌当前是否仍然有效。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @return 有效返回 true，否则返回 false
     */
    boolean isValid(String userId, String jti);

    /**
     * 以原子方式消费刷新令牌。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @return 消费成功返回 true，否则返回 false
     */
    boolean consumeIfValid(String userId, String jti);

    /**
     * 撤销单个刷新令牌。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     */
    void remove(String userId, String jti);

    /**
     * 撤销指定用户的全部刷新令牌。
     *
     * @param userId 用户 ID
     */
    void removeAll(String userId);
}