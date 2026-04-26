package com.zhiguang.be.platform.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.platform.model.PlatformCacheEvictRequest;
import com.zhiguang.be.platform.model.PlatformCacheMetricsData;
import com.zhiguang.be.platform.model.PlatformCacheRegionData;
import com.zhiguang.be.platform.model.PlatformRuntimeData;
import com.zhiguang.be.platform.model.PlatformThreadPoolData;
import com.zhiguang.be.platform.service.PlatformService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台治理控制器。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final PlatformService platformService;

    public PlatformController(PlatformService platformService) {
        this.platformService = platformService;
    }

    /**
     * 查询当前运行摘要。
     */
    @GetMapping("/runtime")
    public ApiResponse<PlatformRuntimeData> runtime() {
        return ApiResponse.success(platformService.getRuntimeSummary());
    }

    /**
     * 查询本地缓存区域摘要。
     */
    @GetMapping("/cache/regions")
    public ApiResponse<List<PlatformCacheRegionData>> cacheRegions() {
        return ApiResponse.success(platformService.listCacheRegions());
    }

    /**
     * 查询缓存整体指标。
     */
    @GetMapping("/cache/metrics")
    public ApiResponse<PlatformCacheMetricsData> cacheMetrics() {
        return ApiResponse.success(platformService.getCacheMetrics());
    }

    /**
     * 查询线程池运行摘要。
     */
    @GetMapping("/threadpools")
    public ApiResponse<List<PlatformThreadPoolData>> threadPools() {
        return ApiResponse.success(platformService.listThreadPools());
    }

    /**
     * 手动清理缓存。
     */
    @PostMapping("/cache/evict")
    public ApiResponse<String> evictCache(@Valid @RequestBody PlatformCacheEvictRequest request) {
        return ApiResponse.success(platformService.evictCache(request));
    }
}
