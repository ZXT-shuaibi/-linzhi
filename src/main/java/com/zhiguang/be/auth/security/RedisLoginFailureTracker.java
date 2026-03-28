package com.zhiguang.be.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的登录失败跟踪器。
 * 使用带过期时间的计数器记录失败次数，支持验证码和封禁阈值判断。
 */
@Component
public class RedisLoginFailureTracker implements LoginFailureTracker {

    private static final String FAILURE_KEY_PREFIX = "auth:login:failure:";
    private static final int CAPTCHA_THRESHOLD = 3;
    private static final int BLOCK_THRESHOLD = 10;
    private static final Duration FAILURE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造 Redis 登录失败跟踪器。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisLoginFailureTracker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 记录一次失败并在首次创建计数器时设置过期时间。
     *
     * @param identifier 登录标识
     */
    @Override
    public void recordFailure(String identifier) {
        String key = toKey(identifier);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, FAILURE_TTL);
        }
    }

    /**
     * 获取当前失败次数。
     *
     * @param identifier 登录标识
     * @return 失败次数，不存在时返回 0
     */
    @Override
    public int getFailureCount(String identifier) {
        String value = redisTemplate.opsForValue().get(toKey(identifier));
        return value != null ? Integer.parseInt(value) : 0;
    }

    /**
     * 判断是否达到验证码阈值。
     *
     * @param identifier 登录标识
     * @return 达到阈值返回 true
     */
    @Override
    public boolean requiresCaptcha(String identifier) {
        return getFailureCount(identifier) >= CAPTCHA_THRESHOLD;
    }

    /**
     * 判断是否达到封禁阈值。
     *
     * @param identifier 登录标识
     * @return 达到阈值返回 true
     */
    @Override
    public boolean shouldBlock(String identifier) {
        return getFailureCount(identifier) >= BLOCK_THRESHOLD;
    }

    /**
     * 清除指定登录标识的失败计数。
     *
     * @param identifier 登录标识
     */
    @Override
    public void reset(String identifier) {
        redisTemplate.delete(toKey(identifier));
    }

    /**
     * 生成失败计数 key。
     *
     * @param identifier 登录标识
     * @return Redis key
     */
    private String toKey(String identifier) {
        return FAILURE_KEY_PREFIX + identifier;
    }
}