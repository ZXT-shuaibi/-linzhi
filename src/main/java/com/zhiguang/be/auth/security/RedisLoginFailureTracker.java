package com.zhiguang.be.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 登录失败追踪器实现。
 */
@Component
public class RedisLoginFailureTracker implements LoginFailureTracker {

    private static final String FAILURE_KEY_PREFIX = "auth:login:failure:";
    private static final int CAPTCHA_THRESHOLD = 3;
    private static final int BLOCK_THRESHOLD = 10;
    private static final Duration FAILURE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    public RedisLoginFailureTracker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void recordFailure(String identifier) {
        String key = toKey(identifier);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, FAILURE_TTL);
        }
    }

    @Override
    public int getFailureCount(String identifier) {
        String value = redisTemplate.opsForValue().get(toKey(identifier));
        return value != null ? Integer.parseInt(value) : 0;
    }

    @Override
    public boolean requiresCaptcha(String identifier) {
        return getFailureCount(identifier) >= CAPTCHA_THRESHOLD;
    }

    @Override
    public boolean shouldBlock(String identifier) {
        return getFailureCount(identifier) >= BLOCK_THRESHOLD;
    }

    @Override
    public void reset(String identifier) {
        redisTemplate.delete(toKey(identifier));
    }

    private String toKey(String identifier) {
        return FAILURE_KEY_PREFIX + identifier;
    }
}
