package com.zhiguang.be.platform.model;

/**
 * 平台 Redis 运行指标。
 */
public record PlatformRedisMetricsData(
        boolean available,
        String ping,
        long dbSize,
        String redisVersion,
        String role,
        String usedMemoryHuman,
        long usedMemoryBytes,
        long connectedClients,
        long blockedClients,
        long expiredKeys,
        long evictedKeys,
        String errorMessage
) {
}
