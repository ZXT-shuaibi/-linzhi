package com.zhiguang.be.auth.blacklist;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 基于 Redis 的访问令牌失效黑名单实现。
 * 以用户 ID 为维度记录“最后一次强制失效时间”，避免旧 access token 在自然过期前继续访问。
 */
@Component
public class RedisAccessTokenBlocklistStore implements AccessTokenBlocklistStore {

    private static final String ACCESS_BLOCKLIST_KEY_PREFIX = "auth:blacklist:access:";
    private static final DefaultRedisScript<Long> UPSERT_BLOCKED_AT_SCRIPT = new DefaultRedisScript<>();

    static {
        UPSERT_BLOCKED_AT_SCRIPT.setResultType(Long.class);
        UPSERT_BLOCKED_AT_SCRIPT.setScriptText(
                "local incoming = tonumber(ARGV[1]); " +
                        "local ttl = tonumber(ARGV[2]); " +
                        "local existing = redis.call('GET', KEYS[1]); " +
                        "if (not existing) or tonumber(existing) < incoming then " +
                        "redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl); " +
                        "return 1; " +
                        "end; " +
                        "local currentTtl = redis.call('PTTL', KEYS[1]); " +
                        "if currentTtl < ttl then " +
                        "redis.call('PEXPIRE', KEYS[1], ttl); " +
                        "end; " +
                        "return 1"
        );
    }

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造 Redis 访问令牌黑名单实现。
     *
     * @param redisTemplate Redis 模板
     */
    public RedisAccessTokenBlocklistStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 记录用户的访问令牌失效时间点。
     * 若并发写入多个时间点，会保留更晚的那个。
     *
     * @param userId 用户 ID
     * @param blockedAt 失效时间点
     * @param ttl 黑名单保留时长
     */
    @Override
    public void block(String userId, Instant blockedAt, Duration ttl) {
        if (!StringUtils.hasText(userId)) {
            return;
        }

        Instant safeBlockedAt = blockedAt == null ? Instant.now() : blockedAt;
        Duration safeTtl = (ttl == null || ttl.isZero() || ttl.isNegative()) ? Duration.ofSeconds(1) : ttl;
        redisTemplate.execute(
                UPSERT_BLOCKED_AT_SCRIPT,
                List.of(toKey(userId)),
                String.valueOf(safeBlockedAt.toEpochMilli()),
                String.valueOf(safeTtl.toMillis())
        );
    }

    /**
     * 判断 access token 是否应该被拒绝。
     *
     * @param userId 用户 ID
     * @param issuedAt access token 的签发时间
     * @return 如果 token 签发时间早于或等于失效时间点，则返回 true
     */
    @Override
    public boolean isBlocked(String userId, Instant issuedAt) {
        if (!StringUtils.hasText(userId) || issuedAt == null) {
            return false;
        }

        String blockedAt = redisTemplate.opsForValue().get(toKey(userId));
        if (!StringUtils.hasText(blockedAt)) {
            return false;
        }

        try {
            long blockedAtMillis = Long.parseLong(blockedAt);
            return !issuedAt.isAfter(Instant.ofEpochMilli(blockedAtMillis));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * 生成用户访问令牌失效黑名单 key。
     *
     * @param userId 用户 ID
     * @return Redis key
     */
    private String toKey(String userId) {
        return ACCESS_BLOCKLIST_KEY_PREFIX + userId;
    }
}
