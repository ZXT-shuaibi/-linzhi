package com.zhiguang.be.cache.hotkey;

import com.zhiguang.be.cache.config.CacheProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * 本地热点 Key 探测器。
 * 通过滑动窗口记录页面访问热度，并按热度等级动态延长缓存 TTL。
 */
@Component
public class HotKeyDetector {

    private final CacheProperties cacheProperties;
    private final ConcurrentHashMap<String, AtomicIntegerArray> counters =
            new ConcurrentHashMap<String, AtomicIntegerArray>();
    private final AtomicInteger currentSegmentIndex = new AtomicInteger(0);
    private final int segments;

    public HotKeyDetector(CacheProperties cacheProperties) {
        this.cacheProperties = cacheProperties;
        int segmentSeconds = Math.max(1, cacheProperties.getHotkey().getSegmentSeconds());
        int windowSeconds = Math.max(segmentSeconds, cacheProperties.getHotkey().getWindowSeconds());
        this.segments = Math.max(1, windowSeconds / segmentSeconds);
    }

    /**
     * 记录一次访问。
     * 每个 Key 只累加当前时间片，窗口轮转后旧时间片会自然清零。
     *
     * @param key 缓存 Key
     */
    public void record(String key) {
        if (!enabled() || !StringUtils.hasText(key)) {
            return;
        }

        int maxTrackedKeys = Math.max(cacheProperties.getHotkey().getMaxTrackedKeys(), 1);
        if (!counters.containsKey(key) && counters.size() >= maxTrackedKeys) {
            return;
        }

        AtomicIntegerArray counter = counters.computeIfAbsent(key, ignored -> new AtomicIntegerArray(segments));
        counter.incrementAndGet(currentSegmentIndex.get());
    }

    /**
     * 计算指定 Key 在当前滑动窗口内的热度。
     *
     * @param key 缓存 Key
     * @return 最近窗口内的访问次数
     */
    public int heat(String key) {
        AtomicIntegerArray counter = counters.get(key);
        if (counter == null) {
            return 0;
        }

        int sum = 0;
        for (int index = 0; index < counter.length(); index++) {
            sum += counter.get(index);
        }
        return sum;
    }

    /**
     * 计算指定 Key 的热度等级。
     *
     * @param key 缓存 Key
     * @return 热度等级
     */
    public Level level(String key) {
        int heat = heat(key);
        CacheProperties.Hotkey hotkey = cacheProperties.getHotkey();
        if (heat >= hotkey.getLevelHigh()) {
            return Level.HIGH;
        }
        if (heat >= hotkey.getLevelMedium()) {
            return Level.MEDIUM;
        }
        if (heat >= hotkey.getLevelLow()) {
            return Level.LOW;
        }
        return Level.NONE;
    }

    /**
     * 根据热度等级计算动态 TTL。
     * 公式：基础 TTL + 热度扩展 TTL + 随机抖动。
     *
     * @param key 缓存 Key
     * @param baseTtl 基础 TTL
     * @return 动态 TTL
     */
    public Duration ttl(String key, Duration baseTtl) {
        if (baseTtl == null || baseTtl.isZero() || baseTtl.isNegative() || !enabled()) {
            return baseTtl;
        }

        long baseSeconds = Math.max(1L, baseTtl.getSeconds());
        long extendSeconds = extendSeconds(level(key));
        long jitterSeconds = jitterSeconds();
        return Duration.ofSeconds(Math.max(1L, baseSeconds + extendSeconds + jitterSeconds));
    }

    /**
     * 轮转当前时间片。
     * 新时间片会被清零，旧时间片会在窗口移动后自然退出统计范围。
     */
    @Scheduled(fixedRateString = "${cache.hotkey.segment-seconds:10}000")
    public void rotate() {
        if (!enabled()) {
            return;
        }

        int nextSegmentIndex = (currentSegmentIndex.get() + 1) % segments;
        currentSegmentIndex.set(nextSegmentIndex);
        for (Map.Entry<String, AtomicIntegerArray> entry : counters.entrySet()) {
            AtomicIntegerArray counter = entry.getValue();
            counter.set(nextSegmentIndex, 0);
            if (isCold(counter)) {
                counters.remove(entry.getKey(), counter);
            }
        }
    }

    /**
     * 清理指定 Key 的热度记录。
     *
     * @param key 缓存 Key
     */
    public void reset(String key) {
        if (StringUtils.hasText(key)) {
            counters.remove(key);
        }
    }

    /**
     * 查询当前跟踪的热点 Key 数量。
     *
     * @return Key 数量
     */
    public int trackedKeyCount() {
        return counters.size();
    }

    private boolean enabled() {
        return cacheProperties.getHotkey().isEnabled();
    }

    private long extendSeconds(Level level) {
        CacheProperties.Hotkey hotkey = cacheProperties.getHotkey();
        switch (level) {
            case HIGH:
                return Math.max(0, hotkey.getExtendHighSeconds());
            case MEDIUM:
                return Math.max(0, hotkey.getExtendMediumSeconds());
            case LOW:
                return Math.max(0, hotkey.getExtendLowSeconds());
            default:
                return 0L;
        }
    }

    private long jitterSeconds() {
        int jitterSeconds = Math.max(0, cacheProperties.getHotkey().getJitterSeconds());
        if (jitterSeconds == 0) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(-jitterSeconds, jitterSeconds + 1L);
    }

    private boolean isCold(AtomicIntegerArray counter) {
        for (int index = 0; index < counter.length(); index++) {
            if (counter.get(index) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 热度等级。
     */
    public enum Level {
        NONE,
        LOW,
        MEDIUM,
        HIGH
    }
}
