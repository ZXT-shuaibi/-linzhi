package com.zhiguang.be.feed;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Feed 缓存 Key 工厂。
 * 统一给页面骨架和条目碎片 Key 加入版本号，便于缓存结构灰度升级和快速整体失效。
 */
public final class FeedCacheKeys {

    private static final String DEFAULT_VERSION = "v1";

    private FeedCacheKeys() {
    }

    /**
     * 构建首页页面缓存 Key，按版本、位置分段和分页参数隔离缓存空间。
     */
    public static String homePageKey(String version, String locationSegment, int page, int size) {
        return "feed:"
                + normalizeVersion(version)
                + ":page:home:"
                + normalizeSegment(locationSegment)
                + ":"
                + page
                + ":"
                + size;
    }

    /**
     * 构建当前版本首页页面缓存的批量失效匹配 Pattern。
     */
    public static String homePagePattern(String version) {
        return "feed:" + normalizeVersion(version) + ":page:home:*";
    }

    /**
     * 构建旧版无版本首页页面缓存 Key，用作滚动发布迁移期的镜像哨兵。
     */
    public static String legacyHomePageKey(String locationSegment, int page, int size) {
        return "feed:page:home:"
                + normalizeSegment(locationSegment)
                + ":"
                + page
                + ":"
                + size;
    }

    /**
     * 构建帖子碎片缓存 Key，碎片与页面使用同一版本命名空间。
     */
    public static String fragmentKey(String version, String postId) {
        return "feed:" + normalizeVersion(version) + ":fragment:post:" + postId;
    }

    /**
     * 旧版无版本首页页面缓存 Pattern，滚动发布迁移期需要同时清理。
     */
    public static String legacyHomePagePattern() {
        return "feed:page:home:*";
    }

    /**
     * 旧版无版本帖子碎片缓存 Key，滚动发布迁移期需要同时清理。
     */
    public static String legacyFragmentKey(String postId) {
        return "feed:fragment:post:" + postId;
    }

    /**
     * 规范化版本号，避免配置中的特殊字符污染 Redis Key。
     */
    private static String normalizeVersion(String version) {
        if (!StringUtils.hasText(version)) {
            return DEFAULT_VERSION;
        }
        String normalized = version.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return normalized.isEmpty() ? DEFAULT_VERSION : normalized;
    }

    /**
     * 规范化位置分段，保证经纬度、GeoHash 等来源生成稳定 Key。
     */
    private static String normalizeSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return "global";
        }
        String normalized = segment.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return normalized.isEmpty() ? "global" : normalized;
    }
}
