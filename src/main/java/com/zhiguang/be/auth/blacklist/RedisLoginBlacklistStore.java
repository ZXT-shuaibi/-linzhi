package com.zhiguang.be.auth.blacklist;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 登录黑名单实现。
 * 约定 key 结构：auth:blacklist:login:{identifier}。
 */
@Component
@ConditionalOnProperty(name = "security.login-blacklist.enabled", havingValue = "true", matchIfMissing = true)
public class RedisLoginBlacklistStore implements LoginBlacklistStore {

    private static final String LOGIN_BLACKLIST_KEY_PREFIX = "auth:blacklist:login:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造函数：注入 Redis 模板。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisLoginBlacklistStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 判断登录标识是否被加入黑名单。
     *
     * @param identifier 登录标识（手机号）
     * @return true 命中黑名单，false 未命中
     */
    @Override
    public boolean isBlocked(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        Boolean exists = redisTemplate.hasKey(toKey(identifier));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 生成登录黑名单 key。
     *
     * @param identifier 登录标识
     * @return Redis key
     */
    private String toKey(String identifier) {
        return LOGIN_BLACKLIST_KEY_PREFIX + identifier;
    }
}
