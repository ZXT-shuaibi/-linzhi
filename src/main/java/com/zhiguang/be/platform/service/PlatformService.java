package com.zhiguang.be.platform.service;

import com.zhiguang.be.platform.model.PlatformCacheEvictRequest;
import com.zhiguang.be.platform.model.PlatformCacheMetricsData;
import com.zhiguang.be.platform.model.PlatformCacheRegionData;
import com.zhiguang.be.platform.model.PlatformHotKeyData;
import com.zhiguang.be.platform.model.PlatformHotKeyResetRequest;
import com.zhiguang.be.platform.model.PlatformObservabilityData;
import com.zhiguang.be.platform.model.PlatformOpsSnapshotData;
import com.zhiguang.be.platform.model.PlatformRedisMetricsData;
import com.zhiguang.be.platform.model.PlatformRuntimeData;
import com.zhiguang.be.platform.model.PlatformThreadPoolData;

import java.util.List;

/**
 * 平台治理服务。
 */
public interface PlatformService {

    /**
     * 查询运行摘要。
     */
    PlatformRuntimeData getRuntimeSummary();

    /**
     * 查询本地缓存区域摘要。
     */
    List<PlatformCacheRegionData> listCacheRegions();

    /**
     * 查询缓存整体指标。
     */
    PlatformCacheMetricsData getCacheMetrics();

    /**
     * 查询 Redis 运行指标。
     */
    PlatformRedisMetricsData getRedisMetrics();

    /**
     * 查询线程池运行摘要。
     */
    List<PlatformThreadPoolData> listThreadPools();

    /**
     * 查询平台可观测性摘要。
     */
    PlatformObservabilityData getObservabilitySummary();

    /**
     * 查询当前热点 Key。
     */
    List<PlatformHotKeyData> listHotKeys(int limit);

    /**
     * 预览匹配模式的 Redis Key。
     */
    List<String> previewRedisKeys(String pattern, int limit);

    /**
     * 重置单个热点 Key 记录。
     */
    String resetHotKey(PlatformHotKeyResetRequest request);

    /**
     * 获取运维治理全景快照。
     */
    PlatformOpsSnapshotData getOpsSnapshot(int hotKeyLimit);

    /**
     * 执行缓存清理。
     */
    String evictCache(PlatformCacheEvictRequest request);
}
