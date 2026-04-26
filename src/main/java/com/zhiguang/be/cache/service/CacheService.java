package com.zhiguang.be.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.config.CacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 统一缓存服务。
 * 负责本地缓存与 Redis 缓存的基础读写，避免业务模块重复处理 JSON 和 TTL 逻辑。
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheProperties cacheProperties;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, LocalCacheEntry>> localRegions =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, LocalCacheEntry>>();
    private final ConcurrentHashMap<String, LocalRegionStats> localRegionStats =
            new ConcurrentHashMap<String, LocalRegionStats>();
    private final LongAdder localHitCount = new LongAdder();
    private final LongAdder localMissCount = new LongAdder();
    private final LongAdder localExpiredCount = new LongAdder();
    private final LongAdder localManualEvictionCount = new LongAdder();
    private final LongAdder localCapacityEvictionCount = new LongAdder();
    private final LongAdder redisReadFailureCount = new LongAdder();
    private final LongAdder redisWriteFailureCount = new LongAdder();
    private final LongAdder redisDeleteFailureCount = new LongAdder();
    private final LongAdder redisPatternDeleteFailureCount = new LongAdder();
    private final LongAdder redisPatternDeletedKeyCount = new LongAdder();

    public CacheService(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            CacheProperties cacheProperties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.cacheProperties = cacheProperties;
    }

    /**
     * 读取本地缓存。
     */
    public <T> T getLocal(String region, String key, Class<T> type) {
        LocalRegionStats stats = regionStats(region);
        ConcurrentHashMap<String, LocalCacheEntry> localCache = localRegions.get(region);
        if (localCache == null) {
            recordLocalMiss(stats);
            return null;
        }
        LocalCacheEntry entry = localCache.get(key);
        if (entry == null) {
            recordLocalMiss(stats);
            return null;
        }
        if (entry.expireAtMillis() < System.currentTimeMillis()) {
            localCache.remove(key);
            recordLocalExpired(stats);
            recordLocalMiss(stats);
            return null;
        }
        Object value = entry.value();
        if (!type.isInstance(value)) {
            recordLocalMiss(stats);
            return null;
        }
        recordLocalHit(stats);
        return type.cast(value);
    }

    /**
     * 写入本地缓存。
     */
    public void putLocal(String region, String key, Object value, Duration ttl) {
        if (value == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        ConcurrentHashMap<String, LocalCacheEntry> localCache =
                localRegions.computeIfAbsent(region, ignored -> new ConcurrentHashMap<String, LocalCacheEntry>());
        regionStats(region);
        localCache.put(key, new LocalCacheEntry(value, System.currentTimeMillis() + ttl.toMillis()));
        shrinkIfNecessary(region, localCache);
    }

    /**
     * 删除本地缓存。
     */
    public void evictLocal(String region, String key) {
        ConcurrentHashMap<String, LocalCacheEntry> localCache = localRegions.get(region);
        if (localCache != null && localCache.remove(key) != null) {
            recordLocalManualEviction(regionStats(region), 1L);
        }
    }

    /**
     * 清空指定本地缓存区域。
     *
     * @param region 缓存区域
     */
    public void evictLocalRegion(String region) {
        if (region != null && !region.trim().isEmpty()) {
            ConcurrentHashMap<String, LocalCacheEntry> removed = localRegions.remove(region);
            if (removed != null && !removed.isEmpty()) {
                recordLocalManualEviction(regionStats(region), removed.size());
            }
            localRegionStats.remove(region);
        }
    }

    /**
     * 获取当前本地缓存区域数量。
     */
    public int localRegionCount() {
        return localRegions.size();
    }

    /**
     * 快照当前本地缓存区域大小。
     */
    public Map<String, Integer> snapshotLocalRegionSizes() {
        Map<String, Integer> snapshot = new ConcurrentHashMap<String, Integer>();
        for (Map.Entry<String, ConcurrentHashMap<String, LocalCacheEntry>> entry : localRegions.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().size());
        }
        return snapshot;
    }

    /**
     * 快照当前本地缓存区域指标。
     */
    public Map<String, LocalRegionSnapshot> snapshotLocalRegionStats() {
        Map<String, LocalRegionSnapshot> snapshot = new LinkedHashMap<String, LocalRegionSnapshot>();
        int maxEntries = Math.max(cacheProperties.getLocal().getMaxEntriesPerRegion(), 16);
        for (Map.Entry<String, ConcurrentHashMap<String, LocalCacheEntry>> entry : localRegions.entrySet()) {
            LocalRegionStats stats = localRegionStats.get(entry.getKey());
            snapshot.put(entry.getKey(), new LocalRegionSnapshot(
                    entry.getValue().size(),
                    maxEntries,
                    sum(stats == null ? null : stats.hitCount),
                    sum(stats == null ? null : stats.missCount),
                    sum(stats == null ? null : stats.expiredCount),
                    sum(stats == null ? null : stats.manualEvictionCount),
                    sum(stats == null ? null : stats.capacityEvictionCount)
            ));
        }
        return snapshot;
    }

    /**
     * 快照缓存整体指标。
     */
    public CacheMetricsSnapshot snapshotMetrics() {
        return new CacheMetricsSnapshot(
                localHitCount.sum(),
                localMissCount.sum(),
                localExpiredCount.sum(),
                localManualEvictionCount.sum(),
                localCapacityEvictionCount.sum(),
                redisReadFailureCount.sum(),
                redisWriteFailureCount.sum(),
                redisDeleteFailureCount.sum(),
                redisPatternDeleteFailureCount.sum(),
                redisPatternDeletedKeyCount.sum()
        );
    }

    /**
     * 读取 Redis 字符串缓存。
     */
    public String getRedisString(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            redisReadFailureCount.increment();
            log.warn("read redis cache failed, key={}", key, ex);
            return null;
        }
    }

    /**
     * 批量读取 Redis 字符串缓存。
     * 用于 Feed 页面装配时一次性获取多个条目碎片，减少 Redis 网络往返。
     *
     * @param keys 缓存 key 列表
     * @return 与 key 顺序一致的缓存值列表，读取失败时返回空列表
     */
    public List<String> getRedisStrings(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
            return values == null ? Collections.emptyList() : values;
        } catch (Exception ex) {
            redisReadFailureCount.increment();
            log.warn("batch read redis cache failed, keyCount={}", keys.size(), ex);
            return Collections.emptyList();
        }
    }

    /**
     * 写入 Redis 字符串缓存。
     */
    public void putRedisString(String key, String value, Duration ttl) {
        if (value == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception ex) {
            redisWriteFailureCount.increment();
            log.warn("write redis string cache failed, key={}", key, ex);
        }
    }

    /**
     * 读取 Redis JSON 缓存。
     */
    public <T> T getRedisJson(String key, Class<T> type) {
        String raw = getRedisString(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception ex) {
            log.warn("deserialize redis json cache failed, key={}", key, ex);
            return null;
        }
    }

    /**
     * 写入 Redis JSON 缓存。
     */
    public void putRedisJson(String key, Object value, Duration ttl) {
        if (value == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            putRedisString(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ex) {
            log.warn("serialize redis json cache failed, key={}", key, ex);
        }
    }

    /**
     * 删除 Redis 缓存。
     */
    public void deleteRedis(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception ex) {
            redisDeleteFailureCount.increment();
            log.warn("delete redis cache failed, key={}", key, ex);
        }
    }

    /**
     * 按 pattern 扫描并删除 Redis 缓存。
     * 使用 SCAN 避免直接 KEYS 阻塞 Redis，适合 Feed 页面批量失效。
     *
     * @param pattern Redis key 匹配表达式
     * @return 删除数量
     */
    public long deleteRedisByPattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return 0L;
        }
        try {
            Long deleted = stringRedisTemplate.execute((RedisCallback<Long>) connection -> {
                ScanOptions options = ScanOptions.scanOptions()
                        .match(pattern)
                        .count(500)
                        .build();
                long count = 0L;
                List<byte[]> batch = new ArrayList<byte[]>();
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() >= 500) {
                            count += deleteBatch(connection, batch);
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    count += deleteBatch(connection, batch);
                }
                return count;
            });
            redisPatternDeletedKeyCount.add(deleted == null ? 0L : deleted.longValue());
            return deleted == null ? 0L : deleted;
        } catch (Exception ex) {
            redisPatternDeleteFailureCount.increment();
            log.warn("delete redis cache by pattern failed, pattern={}", pattern, ex);
            return 0L;
        }
    }

    private long deleteBatch(org.springframework.data.redis.connection.RedisConnection connection, List<byte[]> batch) {
        byte[][] keys = batch.toArray(new byte[batch.size()][]);
        Long deleted = connection.del(keys);
        batch.clear();
        return deleted == null ? 0L : deleted;
    }

    private void shrinkIfNecessary(String region, ConcurrentHashMap<String, LocalCacheEntry> localCache) {
        int maxEntries = Math.max(cacheProperties.getLocal().getMaxEntriesPerRegion(), 16);
        if (localCache.size() <= maxEntries) {
            return;
        }

        long now = System.currentTimeMillis();
        LocalRegionStats stats = regionStats(region);
        Iterator<Map.Entry<String, LocalCacheEntry>> iterator = localCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LocalCacheEntry> entry = iterator.next();
            if (entry.getValue().expireAtMillis() < now) {
                iterator.remove();
                recordLocalExpired(stats);
            }
        }

        int cleanupBatchSize = Math.max(cacheProperties.getLocal().getCleanupBatchSize(), 1);
        if (localCache.size() <= maxEntries) {
            return;
        }
        Iterator<String> keyIterator = localCache.keySet().iterator();
        int removed = 0;
        while (keyIterator.hasNext() && removed < cleanupBatchSize && localCache.size() > maxEntries) {
            keyIterator.next();
            keyIterator.remove();
            removed++;
            recordLocalCapacityEviction(stats, 1L);
        }
    }

    private LocalRegionStats regionStats(String region) {
        return localRegionStats.computeIfAbsent(region == null ? "default" : region, ignored -> new LocalRegionStats());
    }

    private void recordLocalHit(LocalRegionStats stats) {
        localHitCount.increment();
        stats.hitCount.increment();
    }

    private void recordLocalMiss(LocalRegionStats stats) {
        localMissCount.increment();
        stats.missCount.increment();
    }

    private void recordLocalExpired(LocalRegionStats stats) {
        localExpiredCount.increment();
        stats.expiredCount.increment();
    }

    private void recordLocalManualEviction(LocalRegionStats stats, long count) {
        localManualEvictionCount.add(count);
        stats.manualEvictionCount.add(count);
    }

    private void recordLocalCapacityEviction(LocalRegionStats stats, long count) {
        localCapacityEvictionCount.add(count);
        stats.capacityEvictionCount.add(count);
    }

    private long sum(LongAdder adder) {
        return adder == null ? 0L : adder.sum();
    }

    /**
     * 本地缓存条目。
     */
    private record LocalCacheEntry(
            Object value,
            long expireAtMillis
    ) {
    }

    /**
     * 本地缓存区域统计。
     */
    private static final class LocalRegionStats {
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();
        private final LongAdder expiredCount = new LongAdder();
        private final LongAdder manualEvictionCount = new LongAdder();
        private final LongAdder capacityEvictionCount = new LongAdder();
    }

    /**
     * 本地缓存区域快照。
     */
    public record LocalRegionSnapshot(
            int size,
            int maxEntries,
            long hitCount,
            long missCount,
            long expiredCount,
            long manualEvictionCount,
            long capacityEvictionCount
    ) {
    }

    /**
     * 缓存整体指标快照。
     */
    public record CacheMetricsSnapshot(
            long localHitCount,
            long localMissCount,
            long localExpiredCount,
            long localManualEvictionCount,
            long localCapacityEvictionCount,
            long redisReadFailureCount,
            long redisWriteFailureCount,
            long redisDeleteFailureCount,
            long redisPatternDeleteFailureCount,
            long redisPatternDeletedKeyCount
    ) {
    }
}
