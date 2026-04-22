package com.zhiguang.be.feed.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.CacheRegions;
import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.dto.PostAuthor;
import com.zhiguang.be.feed.FeedData;
import com.zhiguang.be.feed.FeedItem;
import com.zhiguang.be.feed.FeedPostRow;
import com.zhiguang.be.feed.mapper.FeedMapper;
import com.zhiguang.be.feed.service.FeedService;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.PageMeta;
import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.UserSocialCounterData;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.InteractionService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 首页 Feed 服务。
 * 当前阶段先对齐技术文档中的基础版要求：
 * 匿名可浏览、支持分页、支持按时间与距离做轻量混排，并补一层 Redis 页面缓存。
 */
@Service
public class FeedServiceImpl implements FeedService {

    private static final long LOCAL_CACHE_TTL_MILLIS = 5_000L;
    private static final Duration PAGE_CACHE_TTL = Duration.ofSeconds(30);
    private static final int DEFAULT_CANDIDATE_WINDOW = 100;
    private static final int MAX_CANDIDATE_WINDOW = 500;
    private static final double EARTH_RADIUS_METERS = 6_371_000D;

    private final FeedMapper feedMapper;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final FollowService followService;
    private final InteractionService interactionService;
    private final UserSocialCounterService userSocialCounterService;
    private final ConcurrentHashMap<String, Object> singleFlightLocks = new ConcurrentHashMap<String, Object>();

    /**
     * 注入 Feed 服务依赖。
     *
     * @param knowPostMapper 内容模块 Mapper
     * @param cacheService 缓存服务
     * @param objectMapper JSON 组件
     */
    public FeedServiceImpl(
            FeedMapper feedMapper,
            CacheService cacheService,
            ObjectMapper objectMapper,
            FollowService followService,
            InteractionService interactionService,
            UserSocialCounterService userSocialCounterService
    ) {
        this.feedMapper = feedMapper;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.followService = followService;
        this.interactionService = interactionService;
        this.userSocialCounterService = userSocialCounterService;
    }

    /**
     * 查询首页 Feed。
     * 如果携带经纬度，则在公开内容候选集上做一层时间和距离混排；
     * 如果没有经纬度，则退化为按发布时间倒序浏览。
     *
     * @param page 页码
     * @param size 每页大小
     * @param lat 可选纬度
     * @param lng 可选经度
     * @param geoHash 可选 GeoHash
     * @return 首页 Feed 分页结果
     */
    @Override
    public FeedData getHomeFeed(int page, int size, Double lat, Double lng, String geoHash, long viewerId) {
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(size);
        validateLocation(lat, lng);

        String cacheKey = buildCacheKey(safePage, safeSize, lat, lng, geoHash);
        FeedData localCached = readLocalCache(cacheKey);
        if (localCached != null) {
            return enrichFeedData(localCached, viewerId, "L1");
        }

        CachedFeedPage cachedPage = readCache(cacheKey);
        if (cachedPage != null) {
            FeedData l2Data = cachedPage.toFeedData("L2");
            writeLocalCache(cacheKey, l2Data);
            return enrichFeedData(l2Data, viewerId, "L2");
        }

        Object lock = singleFlightLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            try {
                FeedData localCachedAgain = readLocalCache(cacheKey);
                if (localCachedAgain != null) {
                    return enrichFeedData(localCachedAgain, viewerId, "L1");
                }

                CachedFeedPage cachedPageAgain = readCache(cacheKey);
                if (cachedPageAgain != null) {
                    FeedData l2Data = cachedPageAgain.toFeedData("L2");
                    writeLocalCache(cacheKey, l2Data);
                    return enrichFeedData(l2Data, viewerId, "L2");
                }

                FeedData freshData = hasLocation(lat, lng)
                        ? buildMixedFeed(safePage, safeSize, lat, lng)
                        : buildLatestFeed(safePage, safeSize);
                writeCache(cacheKey, freshData);
                writeLocalCache(cacheKey, freshData);
                return enrichFeedData(freshData, viewerId, "DB");
            } finally {
                singleFlightLocks.remove(cacheKey);
            }
        }
    }

    /**
     * 构建不带位置的基础首页流。
     *
     * @param page 页码
     * @param size 每页大小
     * @return 首页数据
     */
    private FeedData buildLatestFeed(int page, int size) {
        long total = feedMapper.countHomeFeed();
        int offset = (page - 1) * size;
        List<FeedPostRow> rows = feedMapper.listHomeFeedCandidates(size, offset);
        List<FeedItem> items = new ArrayList<FeedItem>(rows.size());
        for (FeedPostRow row : rows) {
            items.add(toFeedItem(row, null, calculateHotScore(null, row.publishTime(), row.isTop())));
        }
        return new FeedData(items, PageMeta.of(page, size, total), "DB");
    }

    /**
     * 构建带位置的首页流。
     * 当前阶段不直接上完整三级缓存和复杂召回，而是从公开内容中取一批候选后做轻量混排。
     *
     * @param page 页码
     * @param size 每页大小
     * @param lat 纬度
     * @param lng 经度
     * @return 首页数据
     */
    private FeedData buildMixedFeed(int page, int size, double lat, double lng) {
        long total = feedMapper.countHomeFeed();
        int candidateLimit = resolveCandidateLimit(total, page, size);
        List<FeedPostRow> candidates = feedMapper.listHomeFeedCandidates(candidateLimit, 0);
        List<ScoredFeedItem> scoredItems = new ArrayList<ScoredFeedItem>(candidates.size());

        for (FeedPostRow candidate : candidates) {
            Double distanceMeters = calculateDistanceMeters(lat, lng, candidate.latitude(), candidate.longitude());
            double hotScore = calculateHotScore(distanceMeters, candidate.publishTime(), candidate.isTop());
            scoredItems.add(new ScoredFeedItem(
                    candidate,
                    distanceMeters,
                    hotScore
            ));
        }

        scoredItems.sort(Comparator
                .comparing(ScoredFeedItem::isTop).reversed()
                .thenComparing(ScoredFeedItem::hotScore).reversed()
                .thenComparing(ScoredFeedItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ScoredFeedItem::postId, Comparator.reverseOrder()));

        int offset = (page - 1) * size;
        if (offset >= scoredItems.size()) {
            return new FeedData(new ArrayList<FeedItem>(), PageMeta.of(page, size, total), "DB");
        }

        int end = Math.min(offset + size, scoredItems.size());
        List<FeedItem> items = new ArrayList<FeedItem>(end - offset);
        for (int i = offset; i < end; i++) {
            ScoredFeedItem scoredItem = scoredItems.get(i);
            items.add(toFeedItem(scoredItem.row(), scoredItem.distanceMeters(), scoredItem.hotScore()));
        }
        return new FeedData(items, PageMeta.of(page, size, total), "DB");
    }

    /**
     * 将内容行对象转换成 Feed 卡片。
     *
     * @param row 内容行对象
     * @param distanceMeters 距离，单位米
     * @param hotScore 热度分
     * @return Feed 卡片
     */
    private FeedItem toFeedItem(FeedPostRow row, Double distanceMeters, Double hotScore) {
        List<String> imageUrls = parseStringList(row.imgUrlsJson());
        return new FeedItem(
                row.postId(),
                row.title(),
                row.description(),
                imageUrls.isEmpty() ? null : imageUrls.get(0),
                parseStringList(row.tagsJson()),
                new PostAuthor(row.creatorId(), row.authorNickname(), row.authorAvatar(), null, null),
                null,
                null,
                null,
                null,
                distanceMeters,
                hotScore,
                row.isTop(),
                row.publishTime()
        );
    }

    /**
     * 叠加互动汇总与用户态。
     * 公共缓存中不存用户态字段，返回前再按当前查看者进行覆盖。
     *
     * @param baseFeedData 基础 Feed 数据
     * @param viewerId 当前查看者 ID
     * @param cacheLayer 命中缓存层级
     * @return 叠加后的 Feed 数据
     */
    private FeedData enrichFeedData(FeedData baseFeedData, long viewerId, String cacheLayer) {
        List<FeedItem> baseItems = baseFeedData.items();
        if (baseItems == null || baseItems.isEmpty()) {
            return new FeedData(baseItems, baseFeedData.page(), cacheLayer);
        }

        List<Long> targetIds = new ArrayList<Long>(baseItems.size());
        for (FeedItem item : baseItems) {
            targetIds.add(Long.parseLong(item.postId()));
        }

        Map<String, InteractionSummary> summaryMap = interactionService.summaryBatch(viewerId, "post", targetIds);
        Map<String, PostAuthor> authorMap = loadAuthors(baseItems, viewerId);
        List<FeedItem> enrichedItems = new ArrayList<FeedItem>(baseItems.size());
        for (FeedItem item : baseItems) {
            InteractionSummary summary = summaryMap.get(item.postId());
            PostAuthor author = item.author() == null ? null : authorMap.get(item.author().userId());
            if (author == null) {
                author = item.author();
            }
            enrichedItems.add(new FeedItem(
                    item.postId(),
                    item.title(),
                    item.summary(),
                    item.coverUrl(),
                    item.tags(),
                    author,
                    summary == null ? 0L : summary.getLikeCount(),
                    summary == null ? 0L : summary.getFavoriteCount(),
                    viewerId > 0L && summary != null ? summary.isViewerLiked() : null,
                    viewerId > 0L && summary != null ? summary.isViewerFavorited() : null,
                    item.distanceMeters(),
                    item.hotScore(),
                    item.isTop(),
                    item.publishedAt()
            ));
        }
        return new FeedData(enrichedItems, baseFeedData.page(), cacheLayer);
    }

    /**
     * 批量补齐 Feed 作者信息，统一附带作者社交计数和查看者关系态。
     *
     * @param items Feed 卡片列表
     * @param viewerId 当前查看者 ID
     * @return 以作者 ID 为键的作者信息映射
     */
    private Map<String, PostAuthor> loadAuthors(List<FeedItem> items, long viewerId) {
        Map<String, PostAuthor> authorMap = new java.util.LinkedHashMap<String, PostAuthor>();
        if (items == null || items.isEmpty()) {
            return authorMap;
        }

        for (FeedItem item : items) {
            PostAuthor author = item.author();
            if (author == null || !StringUtils.hasText(author.userId()) || authorMap.containsKey(author.userId())) {
                continue;
            }

            long authorUserId = parseOptionalUserId(author.userId());
            UserSocialCounterData socialCounters = authorUserId > 0L
                    ? userSocialCounterService.getUserSocialCounter(authorUserId)
                    : null;
            RelationStatusData relationStatus = resolveRelationStatus(viewerId, authorUserId);
            authorMap.put(
                    author.userId(),
                    new PostAuthor(author.userId(), author.nickname(), author.avatar(), socialCounters, relationStatus)
            );
        }
        return authorMap;
    }

    /**
     * 解析当前查看者与作者之间的关系态。
     *
     * @param viewerId 当前查看者 ID
     * @param authorUserId 作者用户 ID
     * @return 关系态结果
     */
    private RelationStatusData resolveRelationStatus(long viewerId, long authorUserId) {
        if (viewerId <= 0L || authorUserId <= 0L) {
            return new RelationStatusData(false, false, false);
        }
        return followService.relationStatus(viewerId, authorUserId);
    }

    /**
     * 计算基础混排分数。
     * 第一版先按“置顶加权 + 新鲜度 + 距离”进行简单折算，便于后续平滑演进到完整 Feed 策略。
     *
     * @param distanceMeters 距离，单位米
     * @param publishTime 发布时间
     * @param isTop 是否置顶
     * @return 综合热度分
     */
    private double calculateHotScore(Double distanceMeters, Instant publishTime, Boolean isTop) {
        double topBonus = Boolean.TRUE.equals(isTop) ? 0.2D : 0D;
        double freshnessScore = 0D;
        if (publishTime != null) {
            long ageMillis = Math.max(0L, System.currentTimeMillis() - publishTime.toEpochMilli());
            freshnessScore = 1D / (1D + ageMillis / 86_400_000D);
        }
        double distanceScore = distanceMeters == null ? 0D : 1D / (1D + distanceMeters / 1_000D);
        return roundScore(topBonus + freshnessScore * 0.6D + distanceScore * 0.4D);
    }

    /**
     * 计算两点之间的球面距离。
     *
     * @param viewerLat 查看者纬度
     * @param viewerLng 查看者经度
     * @param postLat 内容纬度
     * @param postLng 内容经度
     * @return 距离，单位米；内容没有位置时返回 null
     */
    private Double calculateDistanceMeters(double viewerLat, double viewerLng, Double postLat, Double postLng) {
        if (postLat == null || postLng == null) {
            return null;
        }

        double latRad1 = Math.toRadians(viewerLat);
        double latRad2 = Math.toRadians(postLat);
        double deltaLat = Math.toRadians(postLat - viewerLat);
        double deltaLng = Math.toRadians(postLng - viewerLng);

        double sinLat = Math.sin(deltaLat / 2D);
        double sinLng = Math.sin(deltaLng / 2D);
        double a = sinLat * sinLat
                + Math.cos(latRad1) * Math.cos(latRad2) * sinLng * sinLng;
        double c = 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
        return roundDistance(EARTH_RADIUS_METERS * c);
    }

    /**
     * 读取页面缓存。
     *
     * @param cacheKey 缓存 key
     * @return 命中时返回缓存页，否则返回 null
     */
    private CachedFeedPage readCache(String cacheKey) {
        return cacheService.getRedisJson(cacheKey, CachedFeedPage.class);
    }

    /**
     * 写入页面缓存。
     *
     * @param cacheKey 缓存 key
     * @param feedData Feed 结果
     */
    private void writeCache(String cacheKey, FeedData feedData) {
        cacheService.putRedisJson(cacheKey, CachedFeedPage.from(feedData), PAGE_CACHE_TTL);
    }

    /**
     * 读取本地 L1 页面缓存。
     *
     * @param cacheKey 缓存 key
     * @return 命中时返回页面结果，否则返回 null
     */
    private FeedData readLocalCache(String cacheKey) {
        return cacheService.getLocal(CacheRegions.FEED_HOME, cacheKey, FeedData.class);
    }

    /**
     * 写入本地 L1 页面缓存。
     *
     * @param cacheKey 缓存 key
     * @param feedData Feed 页面数据
     */
    private void writeLocalCache(String cacheKey, FeedData feedData) {
        cacheService.putLocal(CacheRegions.FEED_HOME, cacheKey, feedData, Duration.ofMillis(LOCAL_CACHE_TTL_MILLIS));
    }

    /**
     * 生成页面缓存 key。
     *
     * @param page 页码
     * @param size 每页大小
     * @param lat 纬度
     * @param lng 经度
     * @param geoHash GeoHash
     * @return 页面缓存 key
     */
    private String buildCacheKey(int page, int size, Double lat, Double lng, String geoHash) {
        return "feed:page:home:"
                + resolveLocationSegment(lat, lng, geoHash)
                + ":"
                + page
                + ":"
                + size;
    }

    /**
     * 解析位置分段。
     *
     * @param lat 纬度
     * @param lng 经度
     * @param geoHash GeoHash
     * @return 位置分段字符串
     */
    private String resolveLocationSegment(Double lat, Double lng, String geoHash) {
        if (StringUtils.hasText(geoHash)) {
            return sanitizeSegment(geoHash);
        }
        if (hasLocation(lat, lng)) {
            return sanitizeSegment(String.format(Locale.ROOT, "%.2f_%.2f", lat, lng));
        }
        return "global";
    }

    /**
     * 决定本次混排需要抓取的候选数。
     *
     * @param total 总量
     * @param page 页码
     * @param size 每页大小
     * @return 候选数
     */
    private int resolveCandidateLimit(long total, int page, int size) {
        long requestedWindow = (long) page * size * 5L;
        long candidateWindow = Math.max(requestedWindow, DEFAULT_CANDIDATE_WINDOW);
        long cappedWindow = Math.min(candidateWindow, MAX_CANDIDATE_WINDOW);
        return (int) Math.min(total, cappedWindow);
    }

    /**
     * 校验坐标参数必须成对出现。
     *
     * @param lat 纬度
     * @param lng 经度
     */
    private void validateLocation(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "lat 和 lng 必须同时传入");
        }
    }

    /**
     * 判断是否携带位置。
     *
     * @param lat 纬度
     * @param lng 经度
     * @return 携带返回 true
     */
    private boolean hasLocation(Double lat, Double lng) {
        return lat != null && lng != null;
    }

    /**
     * 规范化页码。
     *
     * @param page 原始页码
     * @return 安全页码
     */
    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    /**
     * 规范化分页大小。
     *
     * @param size 原始分页大小
     * @return 安全分页大小
     */
    private int normalizePageSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }

    /**
     * 清洗缓存 key 片段。
     *
     * @param value 原始字符串
     * @return 清洗后的结果
     */
    private String sanitizeSegment(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /**
     * 解析 JSON 字符串数组。
     *
     * @param json JSON 数组字符串
     * @return 字符串列表
     */
    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * 保留两位小数的距离值。
     *
     * @param distance 原始距离
     * @return 处理后的距离
     */
    /**
     * 解析可选的用户 ID 字符串。
     *
     * @param userId 用户 ID 字符串
     * @return 合法时返回数值 ID，否则返回 0
     */
    private long parseOptionalUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return 0L;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private Double roundDistance(double distance) {
        return Math.round(distance * 100D) / 100D;
    }

    /**
     * 保留四位小数的热度分。
     *
     * @param score 原始分值
     * @return 处理后的分值
     */
    private double roundScore(double score) {
        return Math.round(score * 10_000D) / 10_000D;
    }

    /**
     * 页面缓存结构。
     * 单独抽成内部记录，避免直接把 cacheLayer 也缓存进去。
     */
    private record CachedFeedPage(
            List<FeedItem> items,
            int page,
            int size,
            long total,
            int totalPages,
            boolean hasNext
    ) {
        /**
         * 从 FeedData 构造缓存对象。
         *
         * @param feedData Feed 数据
         * @return 缓存对象
         */
        private static CachedFeedPage from(FeedData feedData) {
            PageMeta pageMeta = feedData.page();
            return new CachedFeedPage(
                    feedData.items(),
                    pageMeta.getPage(),
                    pageMeta.getSize(),
                    pageMeta.getTotal(),
                    pageMeta.getTotalPages(),
                    pageMeta.isHasNext()
            );
        }

        /**
         * 将缓存对象恢复为对外返回结果。
         *
         * @param cacheLayer 当前命中的缓存层级
         * @return Feed 数据
         */
        private FeedData toFeedData(String cacheLayer) {
            return new FeedData(
                    items,
                    new PageMeta(page, size, total, totalPages, hasNext),
                    cacheLayer
            );
        }
    }

    /**
     * 带分值的候选卡片。
     */
    private record ScoredFeedItem(
            FeedPostRow row,
            Double distanceMeters,
            Double hotScore
    ) {
        /**
         * 判断内容是否置顶。
         *
         * @return 置顶返回 true
         */
        private boolean isTop() {
            return Boolean.TRUE.equals(row.isTop());
        }

        /**
         * 获取发布时间。
         *
         * @return 发布时间
         */
        private Instant publishedAt() {
            return row.publishTime();
        }

        /**
         * 获取文章 ID。
         *
         * @return 文章 ID
         */
        private String postId() {
            return row.postId();
        }
    }
}
