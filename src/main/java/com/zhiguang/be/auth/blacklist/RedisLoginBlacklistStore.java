package com.zhiguang.be.auth.blacklist;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 基于 Redis 的登录黑名单实现。
 * 使用 Hash 结构存储黑名单元数据，支持永久和临时封禁。
 * 约定 key 结构：auth:blacklist:login:{userId}
 */
@Component
@ConditionalOnProperty(name = "security.login-blacklist.enabled", havingValue = "true", matchIfMissing = true)
public class RedisLoginBlacklistStore implements LoginBlacklistStore {

    private static final String LOGIN_BLACKLIST_KEY_PREFIX = "auth:blacklist:login:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造 Redis 黑名单存储实现。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisLoginBlacklistStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 判断用户是否存在对应的 Redis 黑名单 key。
     *
     * @param userId 用户 ID
     * @return 命中黑名单返回 true，否则返回 false
     */
    @Override
    public boolean isBlocked(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        Boolean exists = redisTemplate.hasKey(toKey(userId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 将用户加入黑名单并存储元数据。
     *
     * @param userId 用户 ID
     * @param reason 封禁原因
     * @param ttl 过期时间，null 表示永久封禁
     */
    @Override
    public void block(String userId, String reason, Duration ttl) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String key = toKey(userId);
        redisTemplate.opsForHash().put(key, "reason", reason != null ? reason : "");
        redisTemplate.opsForHash().put(key, "blockedAt", Instant.now().toString());
        if (ttl != null) {
            redisTemplate.expire(key, ttl);
        }
    }

    /**
     * 将用户从黑名单中移除。
     *
     * @param userId 用户 ID
     */
    @Override
    public void unblock(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        redisTemplate.delete(toKey(userId));
    }

    /**
     * 生成指定用户对应的黑名单 key。
     *
     * @param userId 用户 ID
     * @return Redis key
     */
    private String toKey(String userId) {
        return LOGIN_BLACKLIST_KEY_PREFIX + userId;
    }
}