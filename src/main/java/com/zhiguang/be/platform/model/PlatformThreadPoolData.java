package com.zhiguang.be.platform.model;

/**
 * 平台线程池运行摘要。
 */
public record PlatformThreadPoolData(
        String name,
        String threadNamePrefix,
        int corePoolSize,
        int maximumPoolSize,
        int queueCapacity,
        boolean preventRejection,
        int poolSize,
        int activeCount,
        int queuedTaskCount,
        long taskCount,
        long completedTaskCount,
        int largestPoolSize,
        long rejectedCount,
        double cpuLoad
) {
}
