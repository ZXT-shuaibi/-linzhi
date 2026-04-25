package com.zhiguang.be.platform.service;

import com.zhiguang.be.platform.model.PlatformCacheEvictRequest;
import com.zhiguang.be.platform.model.PlatformCacheRegionData;
import com.zhiguang.be.platform.model.PlatformRuntimeData;

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
     * 执行缓存清理。
     */
    String evictCache(PlatformCacheEvictRequest request);
}
