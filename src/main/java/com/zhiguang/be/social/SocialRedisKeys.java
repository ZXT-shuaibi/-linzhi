package com.zhiguang.be.social;

/**
 * 社交模块 Redis Key 生成工具。
 */
public final class SocialRedisKeys {

    private static final String ENTITY_COUNTER_SCHEMA = "s1";
    private static final int BITMAP_CHUNK_SIZE = 32768;

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
     * 返回用户计数采样校验键。
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
        return "cnt:" + ENTITY_COUNTER_SCHEMA + ":" + targetType + ":" + targetId;
    }

    /**
     * 返回二期聚合桶键。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return Redis 键
     */
    public static String aggregateBucketKey(String targetType, long targetId) {
        return "agg:" + ENTITY_COUNTER_SCHEMA + ":" + targetType + ":" + targetId;
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
     * 根据用户 ID 计算位图分片号。
     *
     * @param userId 用户 ID
     * @return 分片号
     */
    public static long chunkOf(long userId) {
        return userId / BITMAP_CHUNK_SIZE;
    }

    /**
     * 根据用户 ID 计算分片内位偏移。
     *
     * @param userId 用户 ID
     * @return 位偏移
     */
    public static long bitOffsetOf(long userId) {
        return userId % BITMAP_CHUNK_SIZE;
    }
}
