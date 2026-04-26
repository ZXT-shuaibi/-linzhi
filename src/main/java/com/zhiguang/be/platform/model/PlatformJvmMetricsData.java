package com.zhiguang.be.platform.model;

/**
 * JVM 与进程级观测指标。
 */
public record PlatformJvmMetricsData(
        long uptimeMillis,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        int liveThreadCount,
        int daemonThreadCount,
        int peakThreadCount,
        int availableProcessors,
        double systemLoadAverage
) {
}
