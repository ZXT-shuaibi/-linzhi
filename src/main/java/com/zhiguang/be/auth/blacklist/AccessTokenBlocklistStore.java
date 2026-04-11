package com.zhiguang.be.auth.blacklist;

import java.time.Duration;
import java.time.Instant;

/**
 * 访问令牌失效黑名单存储接口。
 * 用于记录某个用户“从什么时间点开始，之前签发的 access token 应立即失效”。
 */
public interface AccessTokenBlocklistStore {

    /**
     * 记录指定用户的访问令牌失效时间点。
     *
     * @param userId 用户 ID
     * @param blockedAt 失效生效时间点
     * @param ttl 黑名单保留时长
     */
    void block(String userId, Instant blockedAt, Duration ttl);

    /**
     * 判断当前 access token 是否应该被拒绝。
     *
     * @param userId 用户 ID
     * @param issuedAt access token 的签发时间
     * @return 命中返回 true，否则返回 false
     */
    boolean isBlocked(String userId, Instant issuedAt);
}
