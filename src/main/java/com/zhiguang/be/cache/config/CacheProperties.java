package com.zhiguang.be.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存模块配置。
 */
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    private final Local local = new Local();
    private final Hotkey hotkey = new Hotkey();

    public Local getLocal() {
        return local;
    }

    public Hotkey getHotkey() {
        return hotkey;
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

    public static class Hotkey {
        private boolean enabled = true;
        private int windowSeconds = 60;
        private int segmentSeconds = 10;
        private int levelLow = 50;
        private int levelMedium = 200;
        private int levelHigh = 500;
        private int extendLowSeconds = 20;
        private int extendMediumSeconds = 60;
        private int extendHighSeconds = 120;
        private int jitterSeconds = 5;
        private int maxTrackedKeys = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getSegmentSeconds() {
            return segmentSeconds;
        }

        public void setSegmentSeconds(int segmentSeconds) {
            this.segmentSeconds = segmentSeconds;
        }

        public int getLevelLow() {
            return levelLow;
        }

        public void setLevelLow(int levelLow) {
            this.levelLow = levelLow;
        }

        public int getLevelMedium() {
            return levelMedium;
        }

        public void setLevelMedium(int levelMedium) {
            this.levelMedium = levelMedium;
        }

        public int getLevelHigh() {
            return levelHigh;
        }

        public void setLevelHigh(int levelHigh) {
            this.levelHigh = levelHigh;
        }

        public int getExtendLowSeconds() {
            return extendLowSeconds;
        }

        public void setExtendLowSeconds(int extendLowSeconds) {
            this.extendLowSeconds = extendLowSeconds;
        }

        public int getExtendMediumSeconds() {
            return extendMediumSeconds;
        }

        public void setExtendMediumSeconds(int extendMediumSeconds) {
            this.extendMediumSeconds = extendMediumSeconds;
        }

        public int getExtendHighSeconds() {
            return extendHighSeconds;
        }

        public void setExtendHighSeconds(int extendHighSeconds) {
            this.extendHighSeconds = extendHighSeconds;
        }

        public int getJitterSeconds() {
            return jitterSeconds;
        }

        public void setJitterSeconds(int jitterSeconds) {
            this.jitterSeconds = jitterSeconds;
        }

        public int getMaxTrackedKeys() {
            return maxTrackedKeys;
        }

        public void setMaxTrackedKeys(int maxTrackedKeys) {
            this.maxTrackedKeys = maxTrackedKeys;
        }
    }
}
