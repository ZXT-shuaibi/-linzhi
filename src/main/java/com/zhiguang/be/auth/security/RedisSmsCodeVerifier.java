package com.zhiguang.be.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的短信验证码校验器。
 * 校验成功后会主动删除验证码，避免同一验证码被重复使用。
 */
@Component
public class RedisSmsCodeVerifier implements SmsCodeVerifier {

    private static final String SMS_CODE_KEY_PREFIX = "auth:sms:code:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造 Redis 短信验证码校验器。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisSmsCodeVerifier(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 校验验证码并在成功时立即删除 Redis 中的记录。
     *
     * @param phone 手机号
     * @param code 验证码
     * @return 校验通过返回 true，否则返回 false
     */
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

    /**
     * 生成短信验证码对应的 Redis key。
     *
     * @param phone 手机号
     * @return Redis key
     */
    private String toKey(String phone) {
        return SMS_CODE_KEY_PREFIX + phone;
    }
}