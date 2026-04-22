package com.zhiguang.be.auth.verification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Redis 验证码存储能够真实区分“已过期”和“从未发送/不存在”。
 */
class RedisVerificationCodeStoreTest {

    private static final String SCENE = "register";
    private static final String PHONE = "13800138000";

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private ValueOperations<String, String> valueOperations;
    private RedisVerificationCodeStore store;

    /**
     * 初始化 Redis 模板相关桩，便于聚焦验证码状态判断逻辑。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisVerificationCodeStore(redisTemplate);
    }

    /**
     * 验证主验证码键过期但下发标记仍在时，会识别为“已过期”而不是“不存在”。
     */
    @Test
    void verifyShouldReturnExpiredWhenIssuedMarkerStillExists() {
        when(hashOperations.entries("auth:code:register:13800138000")).thenReturn(Map.of());
        when(redisTemplate.hasKey("auth:code:issued:register:13800138000")).thenReturn(true);

        VerificationCheckResult result = store.verify(SCENE, PHONE, "123456");

        assertEquals(VerificationCodeStatus.EXPIRED, result.status());
    }

    /**
     * 验证主验证码键和下发标记都不存在时，会识别为“从未发送/不存在”。
     */
    @Test
    void verifyShouldReturnNotFoundWhenNoKeyExists() {
        when(hashOperations.entries("auth:code:register:13800138000")).thenReturn(Map.of());
        when(redisTemplate.hasKey("auth:code:issued:register:13800138000")).thenReturn(false);

        VerificationCheckResult result = store.verify(SCENE, PHONE, "123456");

        assertEquals(VerificationCodeStatus.NOT_FOUND, result.status());
    }

    /**
     * 验证保存验证码时会额外写入一个更长 TTL 的下发标记键，用于区分过期状态。
     */
    @Test
    void saveCodeShouldPersistIssuedMarkerWithGracePeriod() {
        store.saveCode(SCENE, PHONE, "123456", Duration.ofMinutes(10), 5);

        verify(hashOperations).put("auth:code:register:13800138000", "code", "123456");
        verify(redisTemplate).expire("auth:code:register:13800138000", Duration.ofMinutes(10));
        verify(valueOperations).set(
                eq("auth:code:issued:register:13800138000"),
                eq("issued"),
                eq(Duration.ofMinutes(40))
        );
    }
}
