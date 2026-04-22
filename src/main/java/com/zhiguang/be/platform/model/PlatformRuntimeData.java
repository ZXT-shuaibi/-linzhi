package com.zhiguang.be.platform.model;

import java.time.Instant;
import java.util.List;

/**
 * 平台运行摘要。
 */
public record PlatformRuntimeData(
        String applicationName,
        Instant generatedAt,
        String llmProvider,
        String llmModel,
        boolean socialKafkaEnabled,
        boolean socialRebuildEnabled,
        boolean tradeKafkaEnabled,
        boolean discoverFailOpenEnabled,
        boolean loginBlacklistEnabled,
        int localCacheMaxEntriesPerRegion,
        List<PlatformModuleStatusData> modules
) {
}
