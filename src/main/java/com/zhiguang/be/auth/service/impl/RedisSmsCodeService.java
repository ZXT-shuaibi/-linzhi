package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.service.SmsCodeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 Redis 的短信验证码服务实现。
 * 使用Redis存储验证码，支持过期时间控制。
 */
@Service
public class RedisSmsCodeService implements SmsCodeService {

    private static final String SMS_CODE_PREFIX = "auth:sms:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造函数：注入Redis模板。
     *
     * @param redisTemplate Redis字符串操作模板
     */
    public RedisSmsCodeService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    /**
     * 生成并存储短信验证码。
     * 生成6位随机数字验证码，存储到Redis中，有效期5分钟。
     * 个人项目，不涉及真实短信发送，直接返回验证码。
     *
     * @param phone 手机号
     * @return 6位数字验证码
     */
    public String generate(String phone) {
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        String key = SMS_CODE_PREFIX + phone.trim();
        redisTemplate.opsForValue().set(key, code, Duration.ofMinutes(5));
        return code;
    }

    @Override
    /**
     * 校验短信验证码。
     * 从Redis中获取存储的验证码并与用户输入进行比对。
     * 验证成功后会删除验证码，防止重复使用。
     *
     * @param phone 手机号
     * @param code 用户输入的验证码
     * @return true 表示验证码有效，false 表示无效或已过期
     */
    public boolean verify(String phone, String code) {
        if (phone == null || code == null || phone.isBlank() || code.isBlank()) {
            return false;
        }

        String key = SMS_CODE_PREFIX + phone.trim();
        String storedCode = redisTemplate.opsForValue().get(key);

        if (storedCode == null) {
            return false;
        }

        boolean valid = storedCode.equals(code.trim());
        if (valid) {
            redisTemplate.delete(key);
        }

        return valid;
    }
}
