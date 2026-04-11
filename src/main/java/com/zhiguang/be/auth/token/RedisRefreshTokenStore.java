package com.zhiguang.be.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的刷新令牌白名单实现。
 * 支持原子消费令牌和按用户批量撤销会话。
 */
@Component
@ConditionalOnProperty(name = "security.refresh-store", havingValue = "redis", matchIfMissing = true)
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String REFRESH_TOKEN_KEY_PREFIX = "auth:rt:";
    private static final String REFRESH_TOKEN_INDEX_PREFIX = "auth:rt:index:";
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> REMOVE_ALL_SCRIPT = new DefaultRedisScript<>();

    static {
        CONSUME_SCRIPT.setResultType(Long.class);
        CONSUME_SCRIPT.setScriptText(
                "if redis.call('EXISTS', KEYS[1]) == 1 then " +
                        "redis.call('DEL', KEYS[1]); " +
                        "redis.call('SREM', KEYS[2], ARGV[1]); " +
                        "return 1 " +
                        "else return 0 end"
        );
        ROTATE_SCRIPT.setResultType(Long.class);
        ROTATE_SCRIPT.setScriptText(
                "if redis.call('EXISTS', KEYS[1]) == 0 then " +
                        "return 0 " +
                        "end " +
                        "redis.call('DEL', KEYS[1]); " +
                        "redis.call('SREM', KEYS[3], ARGV[1]); " +
                        "redis.call('SET', KEYS[2], '1', 'PX', ARGV[3]); " +
                        "redis.call('SADD', KEYS[3], ARGV[2]); " +
                        "local currentTtl = redis.call('PTTL', KEYS[3]); " +
                        "if currentTtl < tonumber(ARGV[3]) then " +
                        "redis.call('PEXPIRE', KEYS[3], ARGV[3]); " +
                        "end " +
                        "return 1"
        );
        REMOVE_ALL_SCRIPT.setResultType(Long.class);
        REMOVE_ALL_SCRIPT.setScriptText(
                "local members = redis.call('SMEMBERS', KEYS[1]); " +
                        "for i = 1, #members do " +
                        "redis.call('DEL', ARGV[1] .. members[i]); " +
                        "end; " +
                        "redis.call('DEL', KEYS[1]); " +
                        "return #members"
        );
    }

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造 Redis 刷新令牌存储实现。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存刷新令牌并维护用户级索引集合。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @param expiresAt 过期时间
     */
    @Override
    public void save(String userId, String jti, Instant expiresAt) {
        String tokenKey = tokenKey(userId, jti);
        String indexKey = indexKey(userId);
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofSeconds(1);
        }

        redisTemplate.opsForValue().set(tokenKey, "1", ttl);
        redisTemplate.opsForSet().add(indexKey, jti);
        Long currentIndexTtl = redisTemplate.getExpire(indexKey, TimeUnit.MILLISECONDS);
        long ttlMillis = ttl.toMillis();
        if (currentIndexTtl == null || currentIndexTtl < ttlMillis) {
            redisTemplate.expire(indexKey, ttl);
        }
    }

    /**
     * 判断刷新令牌 key 是否仍然存在。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @return 存在返回 true，否则返回 false
     */
    @Override
    public boolean isValid(String userId, String jti) {
        Boolean exists = redisTemplate.hasKey(tokenKey(userId, jti));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 使用 Lua 脚本原子消费刷新令牌并同步清理索引。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @return 消费成功返回 true，否则返回 false
     */
    @Override
    public boolean consumeIfValid(String userId, String jti) {
        String tokenKey = tokenKey(userId, jti);
        String indexKey = indexKey(userId);
        Long result = redisTemplate.execute(CONSUME_SCRIPT, List.of(tokenKey, indexKey), jti);
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 使用 Lua 脚本原子完成刷新令牌轮换。
     *
     * @param userId 用户 ID
     * @param oldJti 旧令牌唯一标识
     * @param newJti 新令牌唯一标识
     * @param newExpiresAt 新令牌过期时间
     * @return 轮换成功返回 true，否则返回 false
     */
    @Override
    public boolean rotate(String userId, String oldJti, String newJti, Instant newExpiresAt) {
        Duration ttl = Duration.between(Instant.now(), newExpiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofSeconds(1);
        }

        String oldTokenKey = tokenKey(userId, oldJti);
        String newTokenKey = tokenKey(userId, newJti);
        String indexKey = indexKey(userId);
        Long result = redisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(oldTokenKey, newTokenKey, indexKey),
                oldJti,
                newJti,
                String.valueOf(ttl.toMillis())
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 删除单个刷新令牌及其索引。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     */
    @Override
    public void remove(String userId, String jti) {
        redisTemplate.delete(tokenKey(userId, jti));
        redisTemplate.opsForSet().remove(indexKey(userId), jti);
    }

    /**
     * 删除指定用户的全部刷新令牌。
     *
     * @param userId 用户 ID
     */
    @Override
    public void removeAll(String userId) {
        String indexKey = indexKey(userId);
        String tokenKeyPrefix = REFRESH_TOKEN_KEY_PREFIX + userId + ":";
        redisTemplate.execute(REMOVE_ALL_SCRIPT, List.of(indexKey), tokenKeyPrefix);
    }

    /**
     * 生成单个刷新令牌对应的 Redis key。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @return Redis key
     */
    private String tokenKey(String userId, String jti) {
        return REFRESH_TOKEN_KEY_PREFIX + userId + ":" + jti;
    }

    /**
     * 生成某个用户的刷新令牌索引 key。
     *
     * @param userId 用户 ID
     * @return 索引 Redis key
     */
    private String indexKey(String userId) {
        return REFRESH_TOKEN_INDEX_PREFIX + userId;
    }
}
