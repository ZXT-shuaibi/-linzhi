package com.zhiguang.be.platform.controller;

import com.zhiguang.be.common.api.ApiResponse;
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
import com.zhiguang.be.platform.service.PlatformService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
     * 查询平台可观测性摘要。
     */
    @GetMapping("/observability")
    public ApiResponse<PlatformObservabilityData> observability() {
        return ApiResponse.success(platformService.getObservabilitySummary());
    }

    /**
     * 查询平台全景快照。
     */
    @GetMapping("/snapshot")
    public ApiResponse<PlatformOpsSnapshotData> snapshot(
            @RequestParam(name = "hotKeyLimit", defaultValue = "20") int hotKeyLimit
    ) {
        return ApiResponse.success(platformService.getOpsSnapshot(hotKeyLimit));
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
     * 查询 Redis 运行指标。
     */
    @GetMapping("/redis/metrics")
    public ApiResponse<PlatformRedisMetricsData> redisMetrics() {
        return ApiResponse.success(platformService.getRedisMetrics());
    }

    /**
     * 查询热点 Key。
     */
    @GetMapping("/cache/hotkeys")
    public ApiResponse<List<PlatformHotKeyData>> hotKeys(
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        return ApiResponse.success(platformService.listHotKeys(limit));
    }

    /**
     * 预览匹配的 Redis Key。
     */
    @GetMapping("/cache/redis-keys")
    public ApiResponse<List<String>> previewRedisKeys(
            @RequestParam(name = "pattern") String pattern,
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return ApiResponse.success(platformService.previewRedisKeys(pattern, limit));
    }

    /**
     * 重置单个热点 Key 统计。
     */
    @PostMapping("/cache/hotkeys/reset")
    public ApiResponse<String> resetHotKey(@Valid @RequestBody PlatformHotKeyResetRequest request) {
        return ApiResponse.success(platformService.resetHotKey(request));
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
