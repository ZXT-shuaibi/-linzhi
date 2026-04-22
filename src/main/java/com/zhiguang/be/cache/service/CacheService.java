package com.zhiguang.be.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.config.CacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        ConcurrentHashMap<String, LocalCacheEntry> localCache = localRegions.get(region);
        if (localCache == null) {
            return null;
        }
        LocalCacheEntry entry = localCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtMillis() < System.currentTimeMillis()) {
            localCache.remove(key);
            return null;
        }
        Object value = entry.value();
        return type.isInstance(value) ? type.cast(value) : null;
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
        localCache.put(key, new LocalCacheEntry(value, System.currentTimeMillis() + ttl.toMillis()));
        shrinkIfNecessary(localCache);
    }

    /**
     * 删除本地缓存。
     */
    public void evictLocal(String region, String key) {
        ConcurrentHashMap<String, LocalCacheEntry> localCache = localRegions.get(region);
        if (localCache != null) {
            localCache.remove(key);
        }
    }

    /**
     * 读取 Redis 字符串缓存。
     */
    public String getRedisString(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("read redis cache failed, key={}", key, ex);
            return null;
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
            log.warn("delete redis cache failed, key={}", key, ex);
        }
    }

    private void shrinkIfNecessary(ConcurrentHashMap<String, LocalCacheEntry> localCache) {
        int maxEntries = Math.max(cacheProperties.getLocal().getMaxEntriesPerRegion(), 16);
        if (localCache.size() <= maxEntries) {
            return;
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, LocalCacheEntry>> iterator = localCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LocalCacheEntry> entry = iterator.next();
            if (entry.getValue().expireAtMillis() < now) {
                iterator.remove();
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
        }
    }

    /**
     * 本地缓存条目。
     */
    private record LocalCacheEntry(
            Object value,
            long expireAtMillis
    ) {
    }
}
