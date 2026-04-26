package com.zhiguang.be.platform.model;

import java.util.List;

/**
 * 平台治理全景快照。
 */
public record PlatformOpsSnapshotData(
        PlatformRuntimeData runtime,
        PlatformObservabilityData observability,
        List<PlatformCacheRegionData> cacheRegions,
        List<PlatformHotKeyData> hotKeys
) {
}
