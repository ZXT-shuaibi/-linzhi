package com.zhiguang.be.auth.token;

import java.time.Instant;

/**
 * 刷新令牌白名单存储接口。
 */
public interface RefreshTokenStore {

    /**
     * 保存刷新令牌白名单记录。
     *
     * @param userId 用户 ID
     * @param jti 刷新令牌唯一标识
     * @param expiresAt 过期时间
     */
    void save(String userId, String jti, Instant expiresAt);

    /**
     * 判断刷新令牌是否仍有效（只读检查，不消费）。
     *
     * @param userId 用户 ID
     * @param jti 刷新令牌唯一标识
     * @return true 表示当前仍在白名单内
     */
    boolean isValid(String userId, String jti);

    /**
     * 原子消费刷新令牌：仅当令牌仍有效时移除并返回成功。
     *
     * @param userId 用户 ID
     * @param jti 刷新令牌唯一标识
     * @return true 表示本次消费成功，false 表示令牌不存在/已失效/已被消费
     */
    boolean consumeIfValid(String userId, String jti);

    /**
     * 撤销单个刷新令牌。
     *
     * @param userId 用户 ID
     * @param jti 刷新令牌唯一标识
     */
    void remove(String userId, String jti);

    /**
     * 撤销用户全部刷新令牌。
     *
     * @param userId 用户 ID
     */
    void removeAll(String userId);
}