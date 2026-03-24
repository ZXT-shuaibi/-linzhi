package com.zhiguang.be.auth.blacklist;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录黑名单兜底实现。
 * 当未启用或未装配具体黑名单存储时，默认放行所有登录请求。
 */
@Component
@ConditionalOnMissingBean(LoginBlacklistStore.class)
public class NoopLoginBlacklistStore implements LoginBlacklistStore {

    /**
     * 兜底实现始终返回未命中。
     *
     * @param identifier 登录标识
     * @return 恒为 false
     */
    @Override
    public boolean isBlocked(String identifier) {
        return false;
    }

    @Override
    public void block(String identifier, Duration ttl) {
        // noop
    }
}
