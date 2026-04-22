package com.zhiguang.be.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存模块配置。
 */
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    private final Local local = new Local();

    public Local getLocal() {
        return local;
    }

    public static class Local {
        private int maxEntriesPerRegion = 1024;
        private int cleanupBatchSize = 64;

        public int getMaxEntriesPerRegion() {
            return maxEntriesPerRegion;
        }

        public void setMaxEntriesPerRegion(int maxEntriesPerRegion) {
            this.maxEntriesPerRegion = maxEntriesPerRegion;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }
    }
}
