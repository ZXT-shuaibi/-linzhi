package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.model.CodeScene;
import com.zhiguang.be.auth.model.SendCodeResult;
import com.zhiguang.be.auth.service.VerificationCodeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 Redis 的验证码服务实现。
 */
@Service
public class RedisVerificationCodeService implements VerificationCodeService {

    private static final String CODE_PREFIX = "auth:code:";
    private static final String INTERVAL_PREFIX = "auth:code:interval:";
    private static final String DAILY_PREFIX = "auth:code:daily:";

    private static final int CODE_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 5;
    private static final int DAILY_LIMIT = 10;
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration INTERVAL_TTL = Duration.ofSeconds(60);
    private static final Duration DAILY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public RedisVerificationCodeService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public SendCodeResult send(String phone, CodeScene scene) {
        String normalizedPhone = phone.trim();

        // 检查发送间隔
        String intervalKey = INTERVAL_PREFIX + normalizedPhone;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(intervalKey))) {
            return new SendCodeResult(false, "请等待60秒后再试", null);
        }

        // 检查每日发送上限
        String dailyKey = DAILY_PREFIX + normalizedPhone;
        String dailyCount = redisTemplate.opsForValue().get(dailyKey);
        if (dailyCount != null && Integer.parseInt(dailyCount) >= DAILY_LIMIT) {
            return new SendCodeResult(false, "今日发送次数已达上限", null);
        }

        // 生成验证码
        String code = generateCode();
        String codeKey = buildCodeKey(scene, normalizedPhone);

        // 存储验证码（Hash结构）
        Map<String, String> codeData = Map.of(
            "code", code,
            "maxAttempts", String.valueOf(MAX_ATTEMPTS),
            "attempts", "0"
        );
        redisTemplate.opsForHash().putAll(codeKey, codeData);
        redisTemplate.expire(codeKey, CODE_TTL);

        // 设置发送间隔
        redisTemplate.opsForValue().set(intervalKey, "1", INTERVAL_TTL);

        // 增加每日计数
        if (dailyCount == null) {
            redisTemplate.opsForValue().set(dailyKey, "1", DAILY_TTL);
        } else {
            redisTemplate.opsForValue().increment(dailyKey);
        }

        return new SendCodeResult(true, "验证码已发送", code);
    }

    @Override
    public boolean verify(String phone, CodeScene scene, String code) {
        if (phone == null || code == null || phone.isBlank() || code.isBlank()) {
            return false;
        }

        String normalizedPhone = phone.trim();
        String normalizedCode = code.trim();
        String codeKey = buildCodeKey(scene, normalizedPhone);

        // 使用Lua脚本实现原子操作
        String luaScript = """
            local codeData = redis.call('HGETALL', KEYS[1])
            if #codeData == 0 then
                return 0
            end

            local data = {}
            for i = 1, #codeData, 2 do
                data[codeData[i]] = codeData[i + 1]
            end

            local storedCode = data['code']
            local attempts = tonumber(data['attempts'])
            local maxAttempts = tonumber(data['maxAttempts'])

            if attempts >= maxAttempts then
                redis.call('DEL', KEYS[1])
                return 0
            end

            if storedCode == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end

            local newAttempts = attempts + 1
            redis.call('HSET', KEYS[1], 'attempts', newAttempts)

            if newAttempts >= maxAttempts then
                redis.call('DEL', KEYS[1])
            end

            return 0
            """;

        Long result = redisTemplate.execute(
            (org.springframework.data.redis.core.script.RedisScript<Long>)
                org.springframework.data.redis.core.script.RedisScript.of(luaScript, Long.class),
            java.util.List.of(codeKey),
            normalizedCode
        );

        return result != null && result == 1;
    }

    private String generateCode() {
        return String.format("%0" + CODE_LENGTH + "d",
            ThreadLocalRandom.current().nextInt((int) Math.pow(10, CODE_LENGTH)));
    }

    private String buildCodeKey(CodeScene scene, String phone) {
        return CODE_PREFIX + scene.name().toLowerCase() + ":" + phone;
    }
}
