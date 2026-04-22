package com.zhiguang.be.platform.model;

/**
 * 平台缓存清理请求。
 */
public record PlatformCacheEvictRequest(
        String region,
        String localKey,
        String redisKey
) {
}
