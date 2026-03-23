package com.zhiguang.be.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "security.refresh-store", havingValue = "redis", matchIfMissing = true)
/**
 * Redis 刷新令牌白名单实现。
 */
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String REFRESH_TOKEN_KEY_PREFIX = "auth:rt:";
    private static final String REFRESH_TOKEN_INDEX_PREFIX = "auth:rt:index:";

    /**
     * Redis Lua 脚本：原子校验并消费 refresh token。
     * KEYS[1]=tokenKey, KEYS[2]=indexKey, ARGV[1]=jti
     */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>();

    static {
        CONSUME_SCRIPT.setResultType(Long.class);
        CONSUME_SCRIPT.setScriptText(
                "if redis.call('EXISTS', KEYS[1]) == 1 then " +
                        "redis.call('DEL', KEYS[1]); " +
                        "redis.call('SREM', KEYS[2], ARGV[1]); " +
                        "return 1 " +
                        "else return 0 end"
        );
    }

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造函数。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    /**
     * 保存刷新令牌白名单记录。
     */
    public void save(String userId, String jti, Instant expiresAt) {
        String tokenKey = tokenKey(userId, jti);
        String indexKey = indexKey(userId);
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofSeconds(1);
        }

        redisTemplate.opsForValue().set(tokenKey, "1", ttl);
        // 维护用户级索引，支持全设备登出。
        redisTemplate.opsForSet().add(indexKey, jti);
        redisTemplate.expire(indexKey, ttl);
    }

    @Override
    /**
     * 判断刷新令牌是否有效。
     */
    public boolean isValid(String userId, String jti) {
        Boolean exists = redisTemplate.hasKey(tokenKey(userId, jti));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    /**
     * 原子消费刷新令牌，防止并发重复换发。
     */
    public boolean consumeIfValid(String userId, String jti) {
        String tokenKey = tokenKey(userId, jti);
        String indexKey = indexKey(userId);
        Long result = redisTemplate.execute(CONSUME_SCRIPT, List.of(tokenKey, indexKey), jti);
        return Long.valueOf(1L).equals(result);
    }

    @Override
    /**
     * 撤销单个刷新令牌。
     */
    public void remove(String userId, String jti) {
        redisTemplate.delete(tokenKey(userId, jti));
        redisTemplate.opsForSet().remove(indexKey(userId), jti);
    }

    @Override
    /**
     * 撤销用户全部刷新令牌。
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
     * 生成 refresh token 主键。
     */
    private String tokenKey(String userId, String jti) {
        return REFRESH_TOKEN_KEY_PREFIX + userId + ":" + jti;
    }

    /**
     * 生成 refresh token 索引键。
     */
    private String indexKey(String userId) {
        return REFRESH_TOKEN_INDEX_PREFIX + userId;
    }
}