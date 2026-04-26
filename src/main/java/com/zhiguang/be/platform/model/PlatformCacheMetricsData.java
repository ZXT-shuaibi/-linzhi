package com.zhiguang.be.platform.model;

/**
 * 平台缓存整体指标快照。
 */
public record PlatformCacheMetricsData(
        long localHitCount,
        long localMissCount,
        long localExpiredCount,
        long localManualEvictionCount,
        long localCapacityEvictionCount,
        long redisReadFailureCount,
        long redisWriteFailureCount,
        long redisDeleteFailureCount,
        long redisPatternDeleteFailureCount,
        long redisPatternDeletedKeyCount
) {
}
