package com.zhiguang.be.auth.verification;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Redis 的验证码存储实现。
 * 使用 Hash 保存验证码正文、最大尝试次数与当前已尝试次数。
 */
@Component
public class RedisVerificationCodeStore implements VerificationCodeStore {

    private static final String FIELD_CODE = "code";
    private static final String FIELD_MAX_ATTEMPTS = "maxAttempts";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String EXPIRED_MARKER_VALUE = "issued";
    private static final Duration EXPIRED_MARKER_GRACE = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造 Redis 验证码存储实现。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisVerificationCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存验证码到 Redis，并设置有效期。
     *
     * @param scene 场景编码
     * @param phone 手机号
     * @param code 验证码
     * @param ttl 有效期
     * @param maxAttempts 最大尝试次数
     */
    @Override
    public void saveCode(String scene, String phone, String code, Duration ttl, int maxAttempts) {
        String key = buildKey(scene, phone);
        String markerKey = buildExpiredMarkerKey(scene, phone);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        try {
            ops.put(key, FIELD_CODE, code);
            ops.put(key, FIELD_MAX_ATTEMPTS, String.valueOf(maxAttempts));
            ops.put(key, FIELD_ATTEMPTS, "0");
            redisTemplate.expire(key, ttl);
            redisTemplate.opsForValue().set(markerKey, EXPIRED_MARKER_VALUE, ttl.plus(EXPIRED_MARKER_GRACE));
        } catch (DataAccessException ex) {
            throw new RedisSystemException("Failed to save verification code", ex);
        }
    }

    /**
     * 校验验证码，并根据结果更新尝试次数或删除成功记录。
     *
     * @param scene 场景编码
     * @param phone 手机号
     * @param code 用户输入的验证码
     * @return 校验结果
     */
    @Override
    public VerificationCheckResult verify(String scene, String phone, String code) {
        String key = buildKey(scene, phone);
        String markerKey = buildExpiredMarkerKey(scene, phone);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        Map<String, String> data = ops.entries(key);
        if (data.isEmpty()) {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(markerKey))) {
                return new VerificationCheckResult(VerificationCodeStatus.EXPIRED, 0, 0);
            }
            return new VerificationCheckResult(VerificationCodeStatus.NOT_FOUND, 0, 0);
        }

        String storedCode = data.get(FIELD_CODE);
        int maxAttempts = parseInt(data.get(FIELD_MAX_ATTEMPTS), 5);
        int attempts = parseInt(data.get(FIELD_ATTEMPTS), 0);
        if (attempts >= maxAttempts) {
            return new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, attempts, maxAttempts);
        }

        if (Objects.equals(storedCode, code)) {
            redisTemplate.delete(List.of(key, markerKey));
            return new VerificationCheckResult(VerificationCodeStatus.SUCCESS, attempts, maxAttempts);
        }

        int nextAttempts = attempts + 1;
        ops.put(key, FIELD_ATTEMPTS, String.valueOf(nextAttempts));
        if (nextAttempts >= maxAttempts) {
            return new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, nextAttempts, maxAttempts);
        }
        return new VerificationCheckResult(VerificationCodeStatus.MISMATCH, nextAttempts, maxAttempts);
    }

    /**
     * 删除验证码存储记录。
     *
     * @param scene 场景编码
     * @param phone 手机号
     */
    @Override
    public void invalidate(String scene, String phone) {
        redisTemplate.delete(List.of(buildKey(scene, phone), buildExpiredMarkerKey(scene, phone)));
    }

    /**
     * 生成验证码 Redis 键。
     *
     * @param scene 场景编码
     * @param phone 手机号
     * @return Redis 键名
     */
    private String buildKey(String scene, String phone) {
        return "auth:code:%s:%s".formatted(scene, phone);
    }

    /**
     * 生成用于识别“验证码曾经下发过”的 Redis 标记键。
     * 主验证码键过期后，仍会短暂保留该标记，以便区分“已过期”和“从未发送/不存在”。
     *
     * @param scene 场景编码
     * @param phone 手机号
     * @return 过期标记键名
     */
    private String buildExpiredMarkerKey(String scene, String phone) {
        return "auth:code:issued:%s:%s".formatted(scene, phone);
    }

    /**
     * 解析整数字符串，失败时返回默认值。
     *
     * @param value 原始字符串
     * @param defaultValue 默认值
     * @return 解析后的整数
     */
    private int parseInt(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
