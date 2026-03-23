package com.zhiguang.be.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "security.refresh-store", havingValue = "redis", matchIfMissing = true)
/**
 * 类说明。
 */
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String REFRESH_TOKEN_KEY_PREFIX = "auth:rt:";
    private static final String REFRESH_TOKEN_INDEX_PREFIX = "auth:rt:index:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 方法说明。
     */
    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    /**
     * 方法说明。
     */
    public void save(String userId, String jti, Instant expiresAt) {
        String tokenKey = tokenKey(userId, jti);
        String indexKey = indexKey(userId);
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofSeconds(1);
        }

        redisTemplate.opsForValue().set(tokenKey, "1", ttl);
        // 维护用户级令牌唯一标识索引以支持全端登出。
        redisTemplate.opsForSet().add(indexKey, jti);
        redisTemplate.expire(indexKey, ttl);
    }

    @Override
    /**
     * 方法说明。
     */
    public boolean isValid(String userId, String jti) {
        Boolean exists = redisTemplate.hasKey(tokenKey(userId, jti));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    /**
     * 方法说明。
     */
    public void remove(String userId, String jti) {
        redisTemplate.delete(tokenKey(userId, jti));
        redisTemplate.opsForSet().remove(indexKey(userId), jti);
    }

    @Override
    /**
     * 方法说明。
     */
    public void removeAll(String userId) {
        String indexKey = indexKey(userId);
        Set<String> jtis = redisTemplate.opsForSet().members(indexKey);

        if (jtis != null && !jtis.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (String jti : jtis) {
                keys.add(tokenKey(userId, jti));
            }
            redisTemplate.delete(keys);
        }

        redisTemplate.delete(indexKey);
    }

    /**
     * 方法说明。
     */
    private String tokenKey(String userId, String jti) {
        return REFRESH_TOKEN_KEY_PREFIX + userId + ":" + jti;
    }

    /**
     * 方法说明。
     */
    private String indexKey(String userId) {
        return REFRESH_TOKEN_INDEX_PREFIX + userId;
    }
}
