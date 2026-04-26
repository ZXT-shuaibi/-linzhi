package com.zhiguang.be.platform.model;

/**
 * 平台缓存区域摘要。
 */
public record PlatformCacheRegionData(
        String region,
        int size,
        int maxEntries,
        long hitCount,
        long missCount,
        long expiredCount,
        long manualEvictionCount,
        long capacityEvictionCount
) {
}
