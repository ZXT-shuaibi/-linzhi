package com.zhiguang.be.auth.blacklist;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 空实现登录黑名单存储。
 * 当项目未配置真实黑名单实现时，作为兜底实现始终放行请求。
 */
@Component
@ConditionalOnMissingBean(LoginBlacklistStore.class)
public class NoopLoginBlacklistStore implements LoginBlacklistStore {

    /**
     * 始终返回未命中黑名单。
     *
     * @param identifier 登录标识
     * @return 固定返回 false
     */
    @Override
    public boolean isBlocked(String identifier) {
        return false;
    }

    @Override
    public void block(String identifier, Duration ttl) {
    }

    @Override
    public void unblock(String identifier) {
    }
}
