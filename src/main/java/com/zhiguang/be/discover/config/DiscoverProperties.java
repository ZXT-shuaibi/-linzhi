package com.zhiguang.be.discover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 发现模块配置。
 * 收口缓存时长、分布式锁等待时间和搜索参数边界，避免服务实现里散落硬编码常量。
 */
@Component
@ConfigurationProperties(prefix = "discover.lbs")
public class DiscoverProperties {

    private String defaultType = "knowledge";
    private int cacheTtlSeconds = 120;
    private int localCacheTtlSeconds = 5;
    private int lockTtlSeconds = 10;
    private int lockWaitTimeoutMillis = 800;
    private int lockRetryIntervalMillis = 40;
    private int maxRedisFetchCount = 1000;
    private int minRadiusMeters = 100;
    private int maxRadiusMeters = 50000;
    private int maxPageSize = 100;

    public String getDefaultType() {
        return defaultType;
    }

    public void setDefaultType(String defaultType) {
        this.defaultType = defaultType;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public int getLocalCacheTtlSeconds() {
        return localCacheTtlSeconds;
    }

    public void setLocalCacheTtlSeconds(int localCacheTtlSeconds) {
        this.localCacheTtlSeconds = localCacheTtlSeconds;
    }

    public int getLockTtlSeconds() {
        return lockTtlSeconds;
    }

    public void setLockTtlSeconds(int lockTtlSeconds) {
        this.lockTtlSeconds = lockTtlSeconds;
    }

    public int getLockWaitTimeoutMillis() {
        return lockWaitTimeoutMillis;
    }

    public void setLockWaitTimeoutMillis(int lockWaitTimeoutMillis) {
        this.lockWaitTimeoutMillis = lockWaitTimeoutMillis;
    }

    public int getLockRetryIntervalMillis() {
        return lockRetryIntervalMillis;
    }

    public void setLockRetryIntervalMillis(int lockRetryIntervalMillis) {
        this.lockRetryIntervalMillis = lockRetryIntervalMillis;
    }

    public int getMaxRedisFetchCount() {
        return maxRedisFetchCount;
    }

    public void setMaxRedisFetchCount(int maxRedisFetchCount) {
        this.maxRedisFetchCount = maxRedisFetchCount;
    }

    public int getMinRadiusMeters() {
        return minRadiusMeters;
    }

    public void setMinRadiusMeters(int minRadiusMeters) {
        this.minRadiusMeters = minRadiusMeters;
    }

    public int getMaxRadiusMeters() {
        return maxRadiusMeters;
    }

    public void setMaxRadiusMeters(int maxRadiusMeters) {
        this.maxRadiusMeters = maxRadiusMeters;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }
}
