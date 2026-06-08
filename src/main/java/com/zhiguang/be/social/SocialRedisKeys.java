package com.zhiguang.be.social;

/**
 * 社交模块 Redis Key 生成工具。
 */
public final class SocialRedisKeys {

    private SocialRedisKeys() {
    }

    /**
     * 返回用户维计数 SDS 键。
     *
     * @param userId 用户 ID
     * @return Redis 键
     */
    public static String userCounterKey(long userId) {
        return "ucnt:" + userId;
    }

    /**
     * 返回用户计数抽样校验键。
     *
     * @param userId 用户 ID
     * @return Redis 键
     */
    public static String userCounterCheckKey(long userId) {
        return "ucnt:chk:" + userId;
    }

    /**
     * 返回实体计数 SDS 键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 键
     */
    public static String entityCounterKey(String targetType, long targetId) {
        return entityCounterKey(targetType, String.valueOf(targetId));
    }

    /**
     * 返回实体计数 SDS 键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 键
     */
    public static String entityCounterKey(String targetType, String targetId) {
        return "cnt:" + SocialCounterSchema.SCHEMA_ID + ":" + targetType + ":" + targetId;
    }

    /**
     * 返回聚合桶键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 键
     */
    public static String aggregateBucketKey(String targetType, long targetId) {
        return aggregateBucketKey(targetType, String.valueOf(targetId));
    }

    /**
     * 返回聚合桶键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 键
     */
    public static String aggregateBucketKey(String targetType, String targetId) {
        return "agg:" + SocialCounterSchema.SCHEMA_ID + ":" + targetType + ":" + targetId;
    }

    /**
     * 返回聚合桶扫描模式。
     *
     * @return Redis 模式串
     */
    public static String aggregateBucketPattern() {
        return "agg:" + SocialCounterSchema.SCHEMA_ID + ":*";
    }

    /**
     * 返回计数事件幂等去重键。
     *
     * @param eventId 业务事件 ID
     * @return Redis 键
     */
    public static String counterEventDedupKey(String eventId) {
        return "dedup:counter:" + eventId;
    }

    /**
     * 返回计数灾难回放事件幂等去重键。
     *
     * @param eventId 业务事件 ID
     * @return Redis 键
     */
    public static String counterRebuildDedupKey(String eventId) {
        return "dedup:counter-rebuild:" + eventId;
    }

    /**
     * 返回实体计数重建锁键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 锁键
     */
    public static String entityCounterRebuildLockKey(String targetType, long targetId) {
        return "lock:cnt-rebuild:" + targetType + ":" + targetId;
    }

    /**
     * 返回实体计数重建退避指数键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 键
     */
    public static String entityCounterRebuildBackoffExpKey(String targetType, long targetId) {
        return "backoff:cnt-rebuild:exp:" + targetType + ":" + targetId;
    }

    /**
     * 返回实体计数重建退避截止时间键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 键
     */
    public static String entityCounterRebuildBackoffUntilKey(String targetType, long targetId) {
        return "backoff:cnt-rebuild:until:" + targetType + ":" + targetId;
    }

    /**
     * 返回实体计数重建限流键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 键
     */
    public static String entityCounterRebuildRateLimitKey(String targetType, long targetId) {
        return "rl:cnt-rebuild:" + targetType + ":" + targetId;
    }

    /**
     * 返回关注列表 ZSet 键。
     *
     * @param userId 用户 ID
     * @return Redis 键
     */
    public static String followingKey(long userId) {
        return "uf:flws:" + userId;
    }

    /**
     * 返回粉丝列表 ZSet 键。
     *
     * @param userId 用户 ID
     * @return Redis 键
     */
    public static String followerKey(long userId) {
        return "uf:fans:" + userId;
    }

    /**
     * 返回关注/粉丝列表的空标记哨兵键。
     *
     * @param userId 用户 ID
     * @param followingMode true 表示关注列表，false 表示粉丝列表
     * @return Redis 哨兵键
     */
    public static String followEmptySentinelKey(long userId, boolean followingMode) {
        return followingMode ? "uf:empty:flws:" + userId : "uf:empty:fans:" + userId;
    }

    /**
     * 返回关系事件幂等去重键。
     *
     * @param eventType 事件类型
     * @param followerId 关注者 ID
     * @param followeeId 被关注者 ID
     * @param eventId 事件 ID
     * @return Redis 键
     */
    public static String relationEventDedupKey(String eventType, long followerId, long followeeId, String eventId) {
        return "dedup:rel:" + eventType + ":" + followerId + ":" + followeeId + ":" + eventId;
    }

    /**
     * 返回分片位图键。
     *
     * @param metric 指标名称
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param chunk 位图分片号
     * @return Redis 键
     */
    public static String bitmapKey(String metric, String targetType, long targetId, long chunk) {
        return "bm:" + metric + ":" + targetType + ":" + targetId + ":" + chunk;
    }

    /**
     * 返回实体位图分片匹配模式。
     *
     * @param metric 指标名称
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 模式串
     */
    public static String bitmapPattern(String metric, String targetType, long targetId) {
        return "bm:" + metric + ":" + targetType + ":" + targetId + ":*";
    }

    /**
     * 根据用户 ID 计算位图分片号。
     *
     * @param userId 用户 ID
     * @return 分片号
     */
    public static long chunkOf(long userId) {
        return BitmapShard.chunkOf(userId);
    }

    /**
     * 根据用户 ID 计算分片内位偏移。
     *
     * @param userId 用户 ID
     * @return 位偏移
     */
    public static long bitOffsetOf(long userId) {
        return BitmapShard.bitOf(userId);
    }

    /**
     * 返回互动位图/聚合桶同步重试标记键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param userId 用户 ID
     * @param action 动作类型
     * @return Redis 键
     */
    public static String interactionRetryKey(String targetType, long targetId, long userId, String action) {
        return "retry:interaction:" + targetType + ":" + targetId + ":" + userId + ":" + action;
    }
}
