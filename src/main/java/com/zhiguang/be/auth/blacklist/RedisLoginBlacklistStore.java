package com.zhiguang.be.auth.blacklist;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的登录黑名单实现。
 * 通过固定前缀的 key 判断某个登录标识是否已经被加入黑名单。
 * 约定 key 结构：auth:blacklist:login:{identifier}
 */
@Component
@ConditionalOnProperty(name = "security.login-blacklist.enabled", havingValue = "true", matchIfMissing = true)
public class RedisLoginBlacklistStore implements LoginBlacklistStore {

    private static final String LOGIN_BLACKLIST_KEY_PREFIX = "auth:blacklist:login:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造 Redis 登录黑名单实现。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisLoginBlacklistStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 判断登录标识是否存在对应的 Redis 黑名单 key。
     *
     * @param identifier 登录标识
     * @return 命中黑名单返回 true，否则返回 false
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
     * 把指定标识写入 Redis 登录黑名单。
     *
     * @param identifier 登录标识
     * @param ttl 黑名单有效时长，为空或非正数时不设置过期
     */
    @Override
    public void block(String identifier, Duration ttl) {
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        String key = toKey(identifier);
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            redisTemplate.opsForValue().set(key, "1");
            return;
        }
        redisTemplate.opsForValue().set(key, "1", ttl);
    }

    /**
     * 从 Redis 登录黑名单中移除指定标识。
     *
     * @param identifier 登录标识
     */
    @Override
    public void unblock(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        redisTemplate.delete(toKey(identifier));
    }

    /**
     * 生成指定登录标识对应的黑名单 key。
     *
     * @param identifier 登录标识
     * @return Redis key
     */
    private String toKey(String identifier) {
        return LOGIN_BLACKLIST_KEY_PREFIX + identifier;
    }
}
