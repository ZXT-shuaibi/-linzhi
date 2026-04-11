package com.zhiguang.be.auth.blacklist;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 访问令牌失效黑名单兜底实现。
 * 当项目没有提供真实存储时，保持不拦截，方便本地开发或局部测试。
 */
@Component
@ConditionalOnMissingBean(AccessTokenBlocklistStore.class)
public class NoopAccessTokenBlocklistStore implements AccessTokenBlocklistStore {

    /**
     * 忽略黑名单写入请求。
     *
     * @param userId 用户 ID
     * @param blockedAt 失效时间点
     * @param ttl 黑名单保留时长
     */
    @Override
    public void block(String userId, Instant blockedAt, Duration ttl) {
    }

    /**
     * 始终返回未命中。
     *
     * @param userId 用户 ID
     * @param issuedAt access token 的签发时间
     * @return 固定返回 false
     */
    @Override
    public boolean isBlocked(String userId, Instant issuedAt) {
        return false;
    }
}
