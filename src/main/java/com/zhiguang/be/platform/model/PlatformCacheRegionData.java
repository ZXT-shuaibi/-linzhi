package com.zhiguang.be.platform.model;

/**
 * 平台缓存区域摘要。
 */
public record PlatformCacheRegionData(
        String region,
        int size
) {
}
