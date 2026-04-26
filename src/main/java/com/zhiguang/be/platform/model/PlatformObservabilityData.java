package com.zhiguang.be.platform.model;

import java.time.Instant;
import java.util.List;

/**
 * 平台可观测性摘要。
 */
public record PlatformObservabilityData(
        Instant generatedAt,
        List<String> actuatorExposedEndpoints,
        PlatformJvmMetricsData jvm,
        PlatformCacheMetricsData cacheMetrics,
        List<PlatformThreadPoolData> threadPools
) {
}
