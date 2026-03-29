package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.service.AuthRiskService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 基于 Redis 的认证风控服务实现。
 */
@Service
public class RedisAuthRiskService implements AuthRiskService {

    private static final String FAIL_COUNT_PREFIX = "auth:fail:";
    private static final int CAPTCHA_THRESHOLD = 3;
    private static final Duration FAIL_COUNT_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public RedisAuthRiskService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean requiresCaptcha(String identifier) {
        String key = FAIL_COUNT_PREFIX + identifier;
        String count = redisTemplate.opsForValue().get(key);
        if (count == null) {
            return false;
        }
        try {
            return Integer.parseInt(count) >= CAPTCHA_THRESHOLD;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void recordFailure(String identifier) {
        String key = FAIL_COUNT_PREFIX + identifier;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, FAIL_COUNT_TTL);
        }
    }

    @Override
    public void clearFailures(String identifier) {
        String key = FAIL_COUNT_PREFIX + identifier;
        redisTemplate.delete(key);
    }
}
