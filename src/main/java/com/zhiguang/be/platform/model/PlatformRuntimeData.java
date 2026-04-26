package com.zhiguang.be.platform.model;

import java.time.Instant;
import java.util.List;

/**
 * 平台运行摘要。
 */
public record PlatformRuntimeData(
        String applicationName,
        List<String> activeProfiles,
        Instant generatedAt,
        String llmProvider,
        String llmModel,
        String searchProvider,
        int ragDefaultTopK,
        boolean socialKafkaEnabled,
        boolean socialRebuildEnabled,
        boolean tradeKafkaEnabled,
        boolean discoverFailOpenEnabled,
        boolean loginBlacklistEnabled,
        boolean cacheHotkeyEnabled,
        int localCacheMaxEntriesPerRegion,
        int localCacheRegionCount,
        List<PlatformModuleStatusData> modules
) {
}
