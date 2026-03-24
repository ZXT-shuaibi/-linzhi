package com.zhiguang.be.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 短信验证码验证器实现。
 */
@Component
public class RedisSmsCodeVerifier implements SmsCodeVerifier {

    private static final String SMS_CODE_KEY_PREFIX = "auth:sms:code:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public RedisSmsCodeVerifier(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean verify(String phone, String code) {
        String key = toKey(phone);
        String storedCode = redisTemplate.opsForValue().get(key);

        if (storedCode != null && storedCode.equals(code)) {
            redisTemplate.delete(key);
            return true;
        }
        return false;
    }

    private String toKey(String phone) {
        return SMS_CODE_KEY_PREFIX + phone;
    }
}
