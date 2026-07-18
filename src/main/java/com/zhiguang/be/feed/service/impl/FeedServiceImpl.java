package com.zhiguang.be.feed.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.CacheRegions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zhiguang.be.cache.hotkey.HotKeyDetector;
import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.geo.GeoDistances;
import com.zhiguang.be.common.util.Jsons;
import com.zhiguang.be.content.dto.PostAuthor;
import com.zhiguang.be.feed.FeedData;
import com.zhiguang.be.feed.FeedCacheKeys;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 首页 Feed 服务。
 * 负责匿名浏览、时间/距离混排、三层缓存装配以及用户态覆盖。
 */
@Service
public class FeedServiceImpl implements FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedServiceImpl.class);
    private static final long LOCAL_CACHE_TTL_MILLIS = 5_000L;
    private static final Duration PAGE_CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration FRAGMENT_CACHE_TTL = Duration.ofSeconds(90);
    private static final int DEFAULT_CANDIDATE_WINDOW = 100;
    private static final int MAX_CANDIDATE_WINDOW = 500;
    private static final int SINGLE_FLIGHT_MAX_LOCKS = 1024;
    private static final long SINGLE_FLIGHT_IDLE_TTL_MILLIS = 30_000L;
    private static final String TRUSTED_LEGACY_PAGE_MIRROR = "__trusted_legacy_page_mirror__";

    private final FeedMapper feedMapper;
    private final CacheService cacheService;
    private final HotKeyDetector hotKeyDetector;
    private final ObjectMapper objectMapper;
    private final FollowService followService;
    private final InteractionService interactionService;
    private final UserSocialCounterService userSocialCounterService;
    private final ConcurrentHashMap<String, SingleFlightLock> singleFlightLocks = new ConcurrentHashMap<String, SingleFlightLock>();

    @Value("${feed.cache.version:v1}")
    private String cacheVersion;

    @Value("${feed.cache.legacy-mirror-validation-enabled:true}")
    private boolean legacyMirrorValidationEnabled;

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
            HotKeyDetector hotKeyDetector,
            ObjectMapper objectMapper,
            FollowService followService,
            InteractionService interactionService,
            UserSocialCounterService userSocialCounterService
    ) {
        this.feedMapper = feedMapper;
        this.cacheService = cacheService;
        this.hotKeyDetector = hotKeyDetector;
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

        String locationSegment = resolveLocationSegment(lat, lng, geoHash);
        String cacheKey = buildCacheKey(locationSegment, safePage, safeSize);
        String legacyCacheKey = buildLegacyCacheKey(locationSegment, safePage, safeSize);
        hotKeyDetector.record(cacheKey);
        FeedData localCached = readLocalCache(cacheKey);
        String legacyPageMirror = readTrustedLegacyPageMirror(cacheKey, legacyCacheKey);
        if (localCached != null && isLocalCacheTrusted(localCached, legacyPageMirror)) {
            return enrichFeedData(localCached, viewerId, "L2");
        }

        FeedData redisCached = readRedisPage(cacheKey, legacyCacheKey, legacyPageMirror, "L1+L0");
        if (redisCached != null) {
            writeLocalCache(cacheKey, redisCached);
            return enrichFeedData(redisCached, viewerId, "L1+L0");
        }

        SingleFlightLock lock = acquireSingleFlightLock(cacheKey);
        synchronized (lock) {
            try {
                FeedData localCachedAgain = readLocalCache(cacheKey);
                legacyPageMirror = readTrustedLegacyPageMirror(cacheKey, legacyCacheKey);
                if (localCachedAgain != null && isLocalCacheTrusted(localCachedAgain, legacyPageMirror)) {
                    return enrichFeedData(localCachedAgain, viewerId, "L2");
                }

                FeedData redisCachedAgain = readRedisPage(cacheKey, legacyCacheKey, legacyPageMirror, "L1+L0");
                if (redisCachedAgain != null) {
                    writeLocalCache(cacheKey, redisCachedAgain);
                    return enrichFeedData(redisCachedAgain, viewerId, "L1+L0");
                }

                FeedData freshData = hasLocation(lat, lng)
                        ? buildMixedFeed(safePage, safeSize, lat, lng)
                        : buildLatestFeed(safePage, safeSize);
                writeRedisCaches(cacheKey, legacyCacheKey, freshData);
                writeLocalCache(cacheKey, freshData);
                return enrichFeedData(freshData, viewerId, "DB");
            } finally {
                releaseSingleFlightLock(cacheKey, lock);
            }
        }
    }

    private SingleFlightLock acquireSingleFlightLock(String cacheKey) {
        evictIdleSingleFlightLocks(false);
        if (!singleFlightLocks.containsKey(cacheKey) && singleFlightLocks.size() >= SINGLE_FLIGHT_MAX_LOCKS) {
            evictIdleSingleFlightLocks(true);
        }
        if (!singleFlightLocks.containsKey(cacheKey) && singleFlightLocks.size() >= SINGLE_FLIGHT_MAX_LOCKS) {
            SingleFlightLock detachedLock = new SingleFlightLock(System.currentTimeMillis(), false);
            detachedLock.retain();
            return detachedLock;
        }

        long now = System.currentTimeMillis();
        AtomicReference<SingleFlightLock> reference = new AtomicReference<SingleFlightLock>();
        singleFlightLocks.compute(cacheKey, (key, existing) -> {
            SingleFlightLock lock = existing == null ? new SingleFlightLock(now, true) : existing;
            lock.retain();
            reference.set(lock);
            return lock;
        });
        return reference.get();
    }

    private void releaseSingleFlightLock(String cacheKey, SingleFlightLock lock) {
        if (lock == null || lock.release() > 0 || !lock.shared()) {
            return;
        }
        singleFlightLocks.remove(cacheKey, lock);
    }

    private void evictIdleSingleFlightLocks(boolean aggressive) {
        if (!aggressive && singleFlightLocks.size() < SINGLE_FLIGHT_MAX_LOCKS) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, SingleFlightLock> entry : singleFlightLocks.entrySet()) {
            SingleFlightLock lock = entry.getValue();
            if ((aggressive && lock.isIdle()) || lock.isIdleExpired(now, SINGLE_FLIGHT_IDLE_TTL_MILLIS)) {
                singleFlightLocks.remove(entry.getKey(), lock);
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
     * 当前阶段先从公开内容中取一批候选后做轻量混排，后续可继续替换为更完整的召回策略。
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
                .thenComparing(Comparator.comparingDouble(ScoredFeedItem::hotScore).reversed())
                .thenComparing(Comparator.comparing(ScoredFeedItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .thenComparing(Comparator.comparing(ScoredFeedItem::postId).reversed()));

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

        boolean needSummary = viewerId > 0L || !hasCompleteCachedCounts(baseItems);
        Map<String, InteractionSummary> summaryMap = needSummary
                ? interactionService.summaryBatch(viewerId, "post", targetIds)
                : new HashMap<String, InteractionSummary>();
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
                    resolveLikeCount(item, summary),
                    resolveFavoriteCount(item, summary),
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
     * 判断基础页面是否已经带有完整计数快照。
     *
     * @param items Feed 条目
     * @return 计数完整返回 true
     */
    private boolean hasCompleteCachedCounts(List<FeedItem> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        for (FeedItem item : items) {
            if (item.likeCount() == null || item.favoriteCount() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析点赞数。
     * 优先使用实时互动汇总，缺失时回退到条目碎片中的计数快照。
     *
     * @param item Feed 条目
     * @param summary 实时互动汇总
     * @return 点赞数
     */
    private long resolveLikeCount(FeedItem item, InteractionSummary summary) {
        if (summary != null) {
            return summary.getLikeCount();
        }
        return item.likeCount() == null ? 0L : item.likeCount();
    }

    /**
     * 解析收藏数。
     * 优先使用实时互动汇总，缺失时回退到条目碎片中的计数快照。
     *
     * @param item Feed 条目
     * @param summary 实时互动汇总
     * @return 收藏数
     */
    private long resolveFavoriteCount(FeedItem item, InteractionSummary summary) {
        if (summary != null) {
            return summary.getFavoriteCount();
        }
        return item.favoriteCount() == null ? 0L : item.favoriteCount();
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
        Double distance = GeoDistances.nullableHaversineMeters(viewerLat, viewerLng, postLat, postLng);
        return distance == null ? null : roundDistance(distance);
    }

    /**
     * 读取 Redis 页面骨架并通过条目碎片装配页面。
     * L1 只保存 ID 列表和分页元数据，L0 保存条目轻量碎片，避免 Redis 页面缓存被用户态污染。
     *
     * @param cacheKey 页面骨架缓存 key
     * @param cacheLayer 命中层级标记
     * @return 装配成功返回 Feed 数据；骨架或碎片缺失时返回 null
     */
    private FeedData readRedisPage(String cacheKey, String legacyCacheKey, String legacyPageMirror, String cacheLayer) {
        if (!StringUtils.hasText(legacyPageMirror)) {
            return null;
        }
        CachedFeedPage cachedPage = cacheService.getRedisJson(cacheKey, CachedFeedPage.class);
        if (cachedPage == null || cachedPage.items() == null) {
            return null;
        }
        return assembleFromFragments(cachedPage, cacheLayer, hotKeyDetector.ttl(cacheKey, FRAGMENT_CACHE_TTL));
    }

    /**
     * 写入 Redis 三层缓存中的 L0 和 L1。
     * 写入顺序遵循“先碎片、后骨架”，避免骨架可见但碎片还没准备好。
     *
     * @param cacheKey 页面骨架缓存 key
     * @param feedData Feed 数据
     */
    private void writeRedisCaches(String cacheKey, String legacyCacheKey, FeedData feedData) {
        Duration fragmentTtl = hotKeyDetector.ttl(cacheKey, FRAGMENT_CACHE_TTL);
        writeFeedFragments(feedData.items(), fragmentTtl);
        CachedFeedPage cachedPage = CachedFeedPage.from(feedData);
        Duration pageTtl = hotKeyDetector.ttl(cacheKey, PAGE_CACHE_TTL);
        cacheService.putRedisJson(cacheKey, cachedPage, pageTtl);
        // 迁移期镜像旧 key：旧节点只会删除 legacy key，新节点据此识别 versioned 缓存是否仍可信。
        cacheService.putRedisJson(legacyCacheKey, cachedPage, pageTtl);
    }

    /**
     * 使用 L0 条目碎片装配页面。
     * 如果碎片部分缺失，会先尝试按缺失 ID 批量回源并回填；仍缺失则放弃本次缓存命中。
     *
     * @param cachedPage 页面骨架
     * @param cacheLayer 命中层级标记
     * @param fragmentTtl 条目碎片 TTL
     * @return 装配成功返回 Feed 数据，否则返回 null
     */
    private FeedData assembleFromFragments(CachedFeedPage cachedPage, String cacheLayer, Duration fragmentTtl) {
        if (cachedPage.items().isEmpty()) {
            return cachedPage.toFeedData(new ArrayList<FeedItem>(), cacheLayer);
        }

        Map<String, CachedFeedFragment> fragmentMap = readFeedFragments(cachedPage.items());
        List<String> missingIds = collectMissingIds(cachedPage.items(), fragmentMap);
        if (!missingIds.isEmpty()) {
            fragmentMap.putAll(refillMissingFragments(missingIds, fragmentTtl));
        }

        List<FeedItem> items = new ArrayList<FeedItem>(cachedPage.items().size());
        for (CachedFeedPageItem pageItem : cachedPage.items()) {
            CachedFeedFragment fragment = fragmentMap.get(pageItem.postId());
            if (fragment == null) {
                return null;
            }
            items.add(fragment.toFeedItem(pageItem.distanceMeters(), pageItem.hotScore()));
        }
        return cachedPage.toFeedData(items, cacheLayer);
    }

    /**
     * 批量读取条目碎片。
     *
     * @param pageItems 页面骨架中的条目索引
     * @return 以帖子 ID 为键的碎片映射
     */
    private Map<String, CachedFeedFragment> readFeedFragments(List<CachedFeedPageItem> pageItems) {
        List<String> fragmentKeys = new ArrayList<String>(pageItems.size());
        List<String> legacyFragmentKeys = new ArrayList<String>(pageItems.size());
        for (CachedFeedPageItem pageItem : pageItems) {
            fragmentKeys.add(buildFragmentKey(pageItem.postId()));
            legacyFragmentKeys.add(FeedCacheKeys.legacyFragmentKey(pageItem.postId()));
        }

        List<String> rawFragments = cacheService.getRedisStrings(fragmentKeys);
        List<String> rawLegacyFragments = cacheService.getRedisStrings(legacyFragmentKeys);
        Map<String, CachedFeedFragment> fragmentMap = new HashMap<String, CachedFeedFragment>();
        for (int index = 0; index < pageItems.size(); index++) {
            String raw = index < rawFragments.size() ? rawFragments.get(index) : null;
            String legacyRaw = index < rawLegacyFragments.size() ? rawLegacyFragments.get(index) : null;
            if (!isSameCachePayload(raw, legacyRaw)) {
                return new HashMap<String, CachedFeedFragment>();
            }
            CachedFeedFragment fragment = parseFragment(raw);
            if (fragment != null) {
                fragmentMap.put(pageItems.get(index).postId(), fragment);
            }
        }
        return fragmentMap;
    }

    /**
     * 校验本地缓存条目的 versioned/legacy 碎片镜像是否一致。
     * 本地缓存没有 Redis 原始内容，必须回查碎片镜像，避免旧节点重建 legacy 后误信旧本地页。
     */
    private boolean areLegacyFragmentMirrorsTrusted(FeedData feedData) {
        if (feedData == null || feedData.items() == null || feedData.items().isEmpty()) {
            return true;
        }
        List<String> fragmentKeys = new ArrayList<String>(feedData.items().size());
        List<String> legacyFragmentKeys = new ArrayList<String>(feedData.items().size());
        for (FeedItem item : feedData.items()) {
            fragmentKeys.add(buildFragmentKey(item.postId()));
            legacyFragmentKeys.add(FeedCacheKeys.legacyFragmentKey(item.postId()));
        }
        List<String> rawFragments = cacheService.getRedisStrings(fragmentKeys);
        List<String> rawLegacyFragments = cacheService.getRedisStrings(legacyFragmentKeys);
        for (int index = 0; index < feedData.items().size(); index++) {
            String raw = index < rawFragments.size() ? rawFragments.get(index) : null;
            String legacyRaw = index < rawLegacyFragments.size() ? rawLegacyFragments.get(index) : null;
            if (!isSameCachePayload(raw, legacyRaw)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 收集页面骨架中缺失的碎片 ID。
     *
     * @param pageItems 页面骨架条目
     * @param fragmentMap 已命中的碎片
     * @return 缺失的帖子 ID 列表
     */
    private List<String> collectMissingIds(List<CachedFeedPageItem> pageItems, Map<String, CachedFeedFragment> fragmentMap) {
        List<String> missingIds = new ArrayList<String>();
        for (CachedFeedPageItem pageItem : pageItems) {
            if (!fragmentMap.containsKey(pageItem.postId())) {
                missingIds.add(pageItem.postId());
            }
        }
        return missingIds;
    }

    /**
     * 按缺失 ID 批量回源条目碎片并写回 L0。
     *
     * @param missingIds 缺失帖子 ID
     * @param fragmentTtl 条目碎片 TTL
     * @return 成功回填的碎片映射
     */
    private Map<String, CachedFeedFragment> refillMissingFragments(List<String> missingIds, Duration fragmentTtl) {
        Map<String, CachedFeedFragment> fragmentMap = new HashMap<String, CachedFeedFragment>();
        if (missingIds == null || missingIds.isEmpty()) {
            return fragmentMap;
        }

        List<FeedPostRow> rows = feedMapper.listHomeFeedRowsByIds(missingIds);
        List<Long> targetIds = new ArrayList<Long>(rows.size());
        for (FeedPostRow row : rows) {
            targetIds.add(Long.parseLong(row.postId()));
        }
        Map<String, InteractionSummary> summaryMap = targetIds.isEmpty()
                ? new HashMap<String, InteractionSummary>()
                : interactionService.summaryBatch(0L, "post", targetIds);
        for (FeedPostRow row : rows) {
            FeedItem item = toFeedItem(row, null, calculateHotScore(null, row.publishTime(), row.isTop()));
            CachedFeedFragment fragment = CachedFeedFragment.from(item, summaryMap.get(item.postId()));
            fragmentMap.put(item.postId(), fragment);
            writeFeedFragment(item.postId(), fragment, fragmentTtl);
        }
        return fragmentMap;
    }

    /**
     * 写入条目级碎片缓存。
     *
     * @param items Feed 条目
     * @param ttl 碎片 TTL
     */
    private void writeFeedFragments(List<FeedItem> items, Duration ttl) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<Long> targetIds = new ArrayList<Long>(items.size());
        for (FeedItem item : items) {
            targetIds.add(Long.parseLong(item.postId()));
        }
        Map<String, InteractionSummary> summaryMap = interactionService.summaryBatch(0L, "post", targetIds);
        for (FeedItem item : items) {
            writeFeedFragment(item.postId(), CachedFeedFragment.from(item, summaryMap.get(item.postId())), ttl);
        }
    }

    /**
     * 写入帖子碎片缓存，并同步写 legacy 镜像以兼容滚动发布期间的旧实例失效事件。
     */
    private void writeFeedFragment(String postId, CachedFeedFragment fragment, Duration ttl) {
        cacheService.putRedisJson(buildFragmentKey(postId), fragment, ttl);
        cacheService.putRedisJson(FeedCacheKeys.legacyFragmentKey(postId), fragment, ttl);
    }

    /**
     * 读取并校验 legacy 页面镜像。
     * 旧实例可能只刷新 legacy，因此 versioned 与 legacy 内容不一致时必须放弃缓存命中。
     */
    private String readTrustedLegacyPageMirror(String cacheKey, String legacyCacheKey) {
        if (!legacyMirrorValidationEnabled) {
            return TRUSTED_LEGACY_PAGE_MIRROR;
        }
        String versioned = cacheService.getRedisString(cacheKey);
        String legacy = cacheService.getRedisString(legacyCacheKey);
        if (!isSameCachePayload(versioned, legacy)) {
            return null;
        }
        return TRUSTED_LEGACY_PAGE_MIRROR;
    }

    private boolean isLocalCacheTrusted(FeedData localCached, String legacyPageMirror) {
        if (localCached == null) {
            return false;
        }
        if (!legacyMirrorValidationEnabled) {
            return true;
        }
        return legacyPageMirror != null && areLegacyFragmentMirrorsTrusted(localCached);
    }

    /**
     * 判断 versioned 和 legacy 的原始缓存内容是否仍然一致。
     */
    private boolean isSameCachePayload(String versioned, String legacy) {
        return StringUtils.hasText(versioned) && StringUtils.hasText(legacy) && versioned.equals(legacy);
    }

    /**
     * 解析 Redis 中的条目碎片。
     *
     * @param raw 原始 JSON
     * @return 解析成功返回碎片，否则返回 null
     */
    private CachedFeedFragment parseFragment(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, CachedFeedFragment.class);
        } catch (Exception ex) {
            log.debug("Failed to parse cached feed fragment, will re-fetch from upstream", ex);
            return null;
        }
    }

    /**
     * 构造条目碎片缓存 key。
     *
     * @param postId 帖子 ID
     * @return 碎片 key
     */
    private String buildFragmentKey(String postId) {
        return FeedCacheKeys.fragmentKey(cacheVersion, postId);
    }

    /**
     * 读取本地 L2 完整页面缓存。
     *
     * @param cacheKey 缓存 key
     * @return 命中时返回页面结果，否则返回 null
     */
    private FeedData readLocalCache(String cacheKey) {
        return cacheService.getLocal(CacheRegions.FEED_HOME, cacheKey, FeedData.class);
    }

    /**
     * 写入本地 L2 完整页面缓存。
     *
     * @param cacheKey 缓存 key
     * @param feedData Feed 页面数据
     */
    private void writeLocalCache(String cacheKey, FeedData feedData) {
        Duration localTtl = hotKeyDetector.ttl(cacheKey, Duration.ofMillis(LOCAL_CACHE_TTL_MILLIS));
        cacheService.putLocal(CacheRegions.FEED_HOME, cacheKey, feedData, localTtl);
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
    private String buildCacheKey(String locationSegment, int page, int size) {
        return FeedCacheKeys.homePageKey(cacheVersion, locationSegment, page, size);
    }

    /**
     * 生成旧版无版本页面缓存 key，用于滚动发布期间的兼容镜像。
     */
    private String buildLegacyCacheKey(String locationSegment, int page, int size) {
        return FeedCacheKeys.legacyHomePageKey(locationSegment, page, size);
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
        return Jsons.parseStringList(objectMapper, json);
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
     * Redis 页面骨架缓存结构。
     * 只保存页面 ID 顺序、分页元数据和页面级排序信息，不保存完整条目与用户态。
     */
    private record CachedFeedPage(
            List<CachedFeedPageItem> items,
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
            List<CachedFeedPageItem> pageItems = new ArrayList<CachedFeedPageItem>();
            if (feedData.items() != null) {
                for (FeedItem item : feedData.items()) {
                    pageItems.add(CachedFeedPageItem.from(item));
                }
            }
            return new CachedFeedPage(
                    pageItems,
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
        private FeedData toFeedData(List<FeedItem> feedItems, String cacheLayer) {
            return new FeedData(
                    feedItems,
                    new PageMeta(page, size, total, totalPages, hasNext),
                    cacheLayer
            );
        }
    }

    /**
     * 页面骨架中的条目索引。
     * 距离和热度分属于本次页面查询结果，因此放在页面骨架而不是通用碎片中。
     */
    private record CachedFeedPageItem(
            String postId,
            Double distanceMeters,
            Double hotScore
    ) {
        private static CachedFeedPageItem from(FeedItem item) {
            return new CachedFeedPageItem(item.postId(), item.distanceMeters(), item.hotScore());
        }
    }

    private static final class SingleFlightLock {

        private final long createdAtMillis;
        private final boolean shared;
        private final AtomicInteger references = new AtomicInteger(0);

        private SingleFlightLock(long createdAtMillis, boolean shared) {
            this.createdAtMillis = createdAtMillis;
            this.shared = shared;
        }

        private void retain() {
            references.incrementAndGet();
        }

        private int release() {
            return references.updateAndGet(current -> Math.max(0, current - 1));
        }

        private boolean shared() {
            return shared;
        }

        private boolean isIdleExpired(long now, long ttlMillis) {
            return isIdle() && now - createdAtMillis >= ttlMillis;
        }

        private boolean isIdle() {
            return references.get() == 0;
        }
    }

    /**
     * Redis 条目碎片缓存结构。
     * 只保存公共可复用字段，不保存 liked/faved 这类用户态。
     */
    private record CachedFeedFragment(
            String postId,
            String title,
            String summary,
            String coverUrl,
            List<String> tags,
            String authorId,
            String authorNickname,
            String authorAvatar,
            Long likeCount,
            Long favoriteCount,
            Boolean isTop,
            Instant publishedAt
    ) {
        private static CachedFeedFragment from(FeedItem item) {
            return from(item, null);
        }

        private static CachedFeedFragment from(FeedItem item, InteractionSummary summary) {
            PostAuthor author = item.author();
            return new CachedFeedFragment(
                    item.postId(),
                    item.title(),
                    item.summary(),
                    item.coverUrl(),
                    item.tags(),
                    author == null ? null : author.userId(),
                    author == null ? null : author.nickname(),
                    author == null ? null : author.avatar(),
                    summary == null ? item.likeCount() : summary.getLikeCount(),
                    summary == null ? item.favoriteCount() : summary.getFavoriteCount(),
                    item.isTop(),
                    item.publishedAt()
            );
        }

        private FeedItem toFeedItem(Double distanceMeters, Double hotScore) {
            return new FeedItem(
                    postId,
                    title,
                    summary,
                    coverUrl,
                    tags == null ? List.of() : tags,
                    new PostAuthor(authorId, authorNickname, authorAvatar, null, null),
                    likeCount,
                    favoriteCount,
                    null,
                    null,
                    distanceMeters,
                    hotScore,
                    isTop,
                    publishedAt
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
