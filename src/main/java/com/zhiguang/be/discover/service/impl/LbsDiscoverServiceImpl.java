package com.zhiguang.be.discover.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.CacheRegions;
import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.discover.config.DiscoverProperties;
import com.zhiguang.be.discover.model.NearbyItem;
import com.zhiguang.be.discover.model.NearbySearchRequest;
import com.zhiguang.be.discover.model.NearbySearchResponse;
import com.zhiguang.be.discover.service.LbsDiscoverService;
import com.zhiguang.be.discover.util.GeoHashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * LBS 发现服务实现。
 * 负责完成附近搜索、缓存读写、热点保护、元数据组装和位置索引维护等完整流程。
 */
@Service
public class LbsDiscoverServiceImpl implements LbsDiscoverService {

    private static final Logger log = LoggerFactory.getLogger(LbsDiscoverServiceImpl.class);

    private static final String GEO_KEY_PREFIX = "geo:";
    private static final String CACHE_KEY_PREFIX = "lbs:result:";
    private static final String CACHE_LOCK_KEY_PREFIX = "lbs:lock:";
    private static final String CACHE_VERSION_KEY_PREFIX = "lbs:version:";
    private static final String CONTENT_KEY_PREFIX = "lbs:content:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final DiscoverProperties discoverProperties;

    @Value("${discover.lbs.fail-open-on-search-error:false}")
    private boolean failOpenOnSearchError;

    /**
     * 构造 LBS 发现服务实现。
     *
     * @param redisTemplate Redis 模板，用于 Geo 和元数据操作
     * @param cacheService 缓存服务
     * @param objectMapper JSON 序列化组件，用于缓存读写
     */
    public LbsDiscoverServiceImpl(
        StringRedisTemplate redisTemplate,
        CacheService cacheService,
        ObjectMapper objectMapper,
        DiscoverProperties discoverProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.discoverProperties = discoverProperties;
    }

    /**
     * 执行附近搜索。
     * 入口处会先校验参数，再尝试命中缓存；缓存未命中时进入带分布式锁的回源流程。
     *
     * @param request 附近搜索请求
     * @return 搜索结果和分页信息
     */
    @Override
    public NearbySearchResponse searchNearby(NearbySearchRequest request) {
        validateSearchRequest(request);
        String type = normalizeType(request.type());
        String cacheKey = buildCacheKey(request, type);
        NearbySearchResponse localCached = cacheService.getLocal(CacheRegions.DISCOVER_NEARBY, cacheKey, NearbySearchResponse.class);
        if (localCached != null) {
            return localCached;
        }
        Optional<NearbySearchResponse> cachedResponse = getCachedResponse(cacheKey);
        if (cachedResponse.isPresent()) {
            log.debug("LBS nearby cache hit. type={}, page={}, size={}, key={}", type, request.page(), request.size(), cacheKey);
            return cachedResponse.get();
        }

        return searchWithLock(request, type, cacheKey);
    }

    /**
     * 使用分布式锁保护缓存回源查询。
     * 只有抢到锁的线程会真正执行搜索，其他线程会短暂等待缓存结果，避免热点 key 击穿。
     *
     * @param request 原始搜索请求
     * @param type 已标准化的内容类型
     * @param cacheKey 当前请求对应的缓存 key
     * @return 搜索结果
     */
    private NearbySearchResponse searchWithLock(NearbySearchRequest request, String type, String cacheKey) {
        String lockKey = CACHE_LOCK_KEY_PREFIX + cacheKey;
        String lockValue = UUID.randomUUID().toString();
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(discoverProperties.getLockWaitTimeoutMillis()).toNanos();

        while (System.nanoTime() < deadlineNanos) {
            if (tryLock(lockKey, lockValue)) {
                try {
                    Optional<NearbySearchResponse> cachedResponse = getCachedResponse(cacheKey);
                    if (cachedResponse.isPresent()) {
                        return cachedResponse.get();
                    }

                    NearbySearchResponse response = performSearch(request, type);
                    cacheResponse(cacheKey, response);
                    return response;
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            }

            Optional<NearbySearchResponse> cachedResponse = getCachedResponse(cacheKey);
            if (cachedResponse.isPresent()) {
                return cachedResponse.get();
            }

            sleepQuietly(Duration.ofMillis(discoverProperties.getLockRetryIntervalMillis()));
        }

        log.warn("LBS nearby search lock wait timed out. key={}, page={}, size={}", cacheKey, request.page(), request.size());
        NearbySearchResponse response = performSearch(request, type);
        cacheResponse(cacheKey, response);
        return response;
    }

    /**
     * 真实执行 Redis Geo 搜索并组装分页结果。
     * 该流程包含 Geo 查询、批量元数据读取、评分排序和分页切片。
     *
     * @param request 搜索请求
     * @param type 已标准化的内容类型
     * @return 搜索结果
     */
    private NearbySearchResponse performSearch(NearbySearchRequest request, String type) {
        String geoKey = GEO_KEY_PREFIX + type;
        Point center = toRedisPoint(request.lat(), request.lng());
        Circle circle = new Circle(center, new Distance(request.radius() / 1000.0, Metrics.KILOMETERS));
        GeoRadiusCommandArgs searchArgs = buildSearchArgs(request);
        String normalizedTag = normalizeOptionalTag(request.tag());

        GeoResults<RedisGeoCommands.GeoLocation<String>> results;
        try {
            results = redisTemplate.opsForGeo().radius(geoKey, circle, searchArgs);
        } catch (Exception ex) {
            if (failOpenOnSearchError) {
                log.warn("LBS nearby geo query failed and fail-open is enabled. type={}, lat={}, lng={}, radius={}",
                    type, request.lat(), request.lng(), request.radius(), ex);
                return emptyResponse(request);
            }
            log.error("Failed to execute LBS nearby geo query. type={}, lat={}, lng={}, radius={}",
                type, request.lat(), request.lng(), request.radius(), ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to search nearby locations");
        }

        if (results == null || results.getContent().isEmpty()) {
            return emptyResponse(request);
        }

        Map<String, LbsContentMetadata> metadataById = batchReadContentMetadata(type, results.getContent());
        List<NearbyItem> sortedItems = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results.getContent()) {
            Optional<NearbyItem> nearbyItem = toNearbyItem(type, result, metadataById, normalizedTag);
            nearbyItem.ifPresent(sortedItems::add);
        }
        sortedItems.sort(Comparator.comparingDouble(NearbyItem::score).reversed());

        int total = sortedItems.size();
        int fromIndex = calculateFromIndex(request.page(), request.size(), total);
        int toIndex = calculateToIndex(fromIndex, request.size(), total);
        List<NearbyItem> pageItems = sortedItems.subList(fromIndex, toIndex);

        if (results.getContent().size() >= calculateRedisCount(request)) {
            log.debug("LBS nearby search hit Redis fetch cap. type={}, requestedPage={}, requestedSize={}, fetched={}",
                type, request.page(), request.size(), results.getContent().size());
        }
        log.debug("LBS nearby search completed. type={}, total={}, page={}, size={}", type, total, request.page(), request.size());
        return new NearbySearchResponse(pageItems, total, request.page(), request.size());
    }

    /**
     * 将单条 Geo 结果转换为业务侧返回项。
     * 会结合批量预取的元数据补足标题、发布时间、点赞数等字段。
     *
     * @param type 已标准化的内容类型
     * @param result 单条 Geo 查询结果
     * @param metadataById 元数据映射表
     * @return 转换后的返回项，若数据不完整则返回空
     */
    private Optional<NearbyItem> toNearbyItem(
        String type,
        GeoResult<RedisGeoCommands.GeoLocation<String>> result,
        Map<String, LbsContentMetadata> metadataById,
        String normalizedTag
    ) {
        RedisGeoCommands.GeoLocation<String> location = result.getContent();
        if (location == null || !StringUtils.hasText(location.getName())) {
            return Optional.empty();
        }

        String id = location.getName();
        LbsContentMetadata metadata = metadataById.getOrDefault(id, LbsContentMetadata.empty());
        if (!matchesTag(normalizedTag, metadata.tags())) {
            return Optional.empty();
        }
        Point point = location.getPoint();
        Double lat = resolveLatitude(point, metadata);
        Double lng = resolveLongitude(point, metadata);
        Double distance = result.getDistance() != null ? result.getDistance().getValue() : null;
        Double score = calculateScore(distance, metadata.publishTime(), metadata.likeCount());

        return Optional.of(new NearbyItem(
            id,
            externalType(type),
            metadata.title(),
            metadata.summary(),
            metadata.coverUrl(),
            metadata.address(),
            metadata.tags(),
            metadata.authorId(),
            metadata.authorName(),
            metadata.authorAvatar(),
            lat,
            lng,
            distance,
            metadata.publishTime(),
            metadata.likeCount(),
            metadata.favoriteCount(),
            score
        ));
    }

    /**
     * 使用 Redis Pipeline 批量读取候选内容元数据。
     * 该方法用于消除逐条 HGETALL 带来的 N+1 往返问题。
     *
     * @param type 已标准化的内容类型
     * @param results Geo 查询结果列表
     * @return 内容 ID 到元数据对象的映射
     */
    private Map<String, LbsContentMetadata> batchReadContentMetadata(
        String type,
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> results
    ) {
        List<String> ids = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            RedisGeoCommands.GeoLocation<String> location = result.getContent();
            if (location != null && StringUtils.hasText(location.getName())) {
                ids.add(location.getName());
            }
        }

        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            List<Object> pipelinedResults = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    for (String id : ids) {
                        operations.opsForHash().entries(buildContentKey(type, id));
                    }
                    return null;
                }
            });

            Map<String, LbsContentMetadata> metadataById = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                Object pipelineResult = pipelinedResults != null && i < pipelinedResults.size() ? pipelinedResults.get(i) : null;
                metadataById.put(ids.get(i), parseMetadata(pipelineResult));
            }
            return metadataById;
        } catch (Exception ex) {
            log.warn("Failed to batch read LBS content metadata. type={}, count={}", type, ids.size(), ex);
            return Collections.emptyMap();
        }
    }

    /**
     * 计算综合排序分数。
     * 当前综合考虑距离、发布时间新鲜度和点赞量三个维度。
     *
     * @param distanceMeters 距离，单位米
     * @param publishTime 发布时间时间戳
     * @param likeCount 点赞数
     * @return 综合分数
     */
    private Double calculateScore(Double distanceMeters, Long publishTime, Integer likeCount) {
        double distanceScore = distanceMeters == null ? 0.0 : 1.0 / (1.0 + distanceMeters / 1000.0);
        double freshnessScore = 0.0;
        if (publishTime != null) {
            long ageMillis = Math.max(0L, System.currentTimeMillis() - publishTime);
            freshnessScore = 1.0 / (1.0 + ageMillis / 86_400_000.0);
        }

        double interactionScore = likeCount == null ? 0.0 : Math.log1p(Math.max(likeCount, 0)) / 10.0;
        return distanceScore * 0.5 + freshnessScore * 0.3 + interactionScore * 0.2;
    }

    /**
     * 构造搜索结果缓存 key。
     * 通过 GeoHash、分页参数、标签和缓存版本组合请求特征，再做哈希压缩，避免 key 过长。
     *
     * @param request 搜索请求
     * @param type 已标准化的内容类型
     * @return 最终缓存 key
     */
    private String buildCacheKey(NearbySearchRequest request, String type) {
        int precision = resolveGeoHashPrecision(request.radius());
        String geoHash = GeoHashUtil.encode(request.lat(), request.lng(), precision);
        long version = resolveCacheVersion(type);
        String tag = sanitizeSegment(StringUtils.hasText(request.tag()) ? request.tag() : "all");
        String rawKey = String.format(
            Locale.ROOT,
            "%s:v%d:%s:r%d:p%d:s%d:t%s",
            type,
            version,
            geoHash,
            request.radius(),
            request.page(),
            request.size(),
            tag
        );
        return CACHE_KEY_PREFIX + type + ":v" + version + ":" + hashCacheKey(rawKey);
    }

    /**
     * 读取搜索结果缓存。
     * 缓存读取失败时不会中断主流程，而是降级为回源查询。
     *
     * @param cacheKey 缓存 key
     * @return 命中时返回结果对象，否则返回空
     */
    private Optional<NearbySearchResponse> getCachedResponse(String cacheKey) {
        NearbySearchResponse cachedResponse = cacheService.getRedisJson(cacheKey, NearbySearchResponse.class);
        if (cachedResponse == null) {
            return Optional.empty();
        }
        cacheService.putLocal(
                CacheRegions.DISCOVER_NEARBY,
                cacheKey,
                cachedResponse,
                Duration.ofSeconds(discoverProperties.getLocalCacheTtlSeconds())
        );
        return Optional.of(cachedResponse);
    }

    /**
     * 写入搜索结果缓存。
     * 缓存写入失败只记录日志，不影响本次查询返回。
     *
     * @param cacheKey 缓存 key
     * @param response 搜索结果
     */
    private void cacheResponse(String cacheKey, NearbySearchResponse response) {
        cacheService.putRedisJson(cacheKey, response, Duration.ofSeconds(discoverProperties.getCacheTtlSeconds()));
        cacheService.putLocal(
                CacheRegions.DISCOVER_NEARBY,
                cacheKey,
                response,
                Duration.ofSeconds(discoverProperties.getLocalCacheTtlSeconds())
        );
    }

    /**
     * 写入 Geo 位置和关联元数据。
     * 成功后会递增缓存版本，让历史搜索缓存自然失效。
     *
     * @param id 内容 ID
     * @param type 内容类型
     * @param lat 纬度
     * @param lng 经度
     * @param title 标题
     * @param publishTime 发布时间
     * @param likeCount 点赞数
     */
    @Override
    public void addLocation(
        String id,
        String type,
        Double lat,
        Double lng,
        String title,
        String summary,
        String coverUrl,
        String address,
        String authorId,
        String authorName,
        String authorAvatar,
        String tagsJson,
        Long publishTime,
        Integer likeCount,
        Integer favoriteCount
    ) {
        String normalizedType = normalizeType(type);
        try {
            redisTemplate.opsForGeo().add(GEO_KEY_PREFIX + normalizedType, toRedisPoint(lat, lng), id);
            saveContentMetadata(
                    normalizedType,
                    id,
                    lat,
                    lng,
                    title,
                    summary,
                    coverUrl,
                    address,
                    authorId,
                    authorName,
                    authorAvatar,
                    tagsJson,
                    publishTime,
                    likeCount,
                    favoriteCount
            );
            bumpCacheVersion(normalizedType);
            log.info("Indexed LBS location. type={}, id={}, lat={}, lng={}", normalizedType, id, lat, lng);
        } catch (Exception ex) {
            log.error("Failed to index LBS location. type={}, id={}", normalizedType, id, ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update location index");
        }
    }

    /**
     * 删除位置索引和元数据。
     * 成功后同样会递增缓存版本，避免返回过期搜索结果。
     *
     * @param id 内容 ID
     * @param type 内容类型
     */
    @Override
    public void removeLocation(String id, String type) {
        String normalizedType = normalizeType(type);
        try {
            redisTemplate.opsForGeo().remove(GEO_KEY_PREFIX + normalizedType, id);
            redisTemplate.delete(buildContentKey(normalizedType, id));
            bumpCacheVersion(normalizedType);
            log.info("Removed LBS location. type={}, id={}", normalizedType, id);
        } catch (Exception ex) {
            log.error("Failed to remove LBS location. type={}, id={}", normalizedType, id, ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update location index");
        }
    }

    /**
     * 增量刷新 discover 卡片中的互动统计。
     * 这里只更新内容元数据，不主动击穿整页缓存，依赖短 TTL 做最终一致。
     *
     * @param id 内容 ID
     * @param type 内容类型
     * @param likeDelta 点赞增量
     * @param favoriteDelta 收藏增量
     */
    @Override
    public void incrementInteractionStats(String id, String type, int likeDelta, int favoriteDelta) {
        if (!StringUtils.hasText(id) || (!hasDelta(likeDelta) && !hasDelta(favoriteDelta))) {
            return;
        }

        String normalizedType = normalizeType(type);
        String contentKey = buildContentKey(normalizedType, id);
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(contentKey))) {
                return;
            }

            if (hasDelta(likeDelta)) {
                updateCounterField(contentKey, "likeCount", likeDelta);
            }
            if (hasDelta(favoriteDelta)) {
                updateCounterField(contentKey, "favoriteCount", favoriteDelta);
            }
        } catch (Exception ex) {
            log.warn("Failed to refresh discover interaction stats. type={}, id={}", normalizedType, id, ex);
        }
    }

    /**
     * 将内容附属元数据保存到 Redis Hash。
     * 这里会写入备用坐标、标题、发布时间和点赞数等搜索结果展示字段。
     *
     * @param type 内容类型
     * @param id 内容 ID
     * @param lat 纬度
     * @param lng 经度
     * @param title 标题
     * @param publishTime 发布时间
     * @param likeCount 点赞数
     */
    private void saveContentMetadata(
        String type,
        String id,
        Double lat,
        Double lng,
        String title,
        String summary,
        String coverUrl,
        String address,
        String authorId,
        String authorName,
        String authorAvatar,
        String tagsJson,
        Long publishTime,
        Integer likeCount,
        Integer favoriteCount
    ) {
        Map<String, String> values = new HashMap<>();
        values.put("lat", String.valueOf(lat));
        values.put("lng", String.valueOf(lng));
        if (StringUtils.hasText(title)) {
            values.put("title", title.trim());
        }
        if (StringUtils.hasText(summary)) {
            values.put("summary", summary.trim());
        }
        if (StringUtils.hasText(coverUrl)) {
            values.put("coverUrl", coverUrl.trim());
        }
        if (StringUtils.hasText(address)) {
            values.put("address", address.trim());
        }
        if (StringUtils.hasText(authorId)) {
            values.put("authorId", authorId.trim());
        }
        if (StringUtils.hasText(authorName)) {
            values.put("authorName", authorName.trim());
        }
        if (StringUtils.hasText(authorAvatar)) {
            values.put("authorAvatar", authorAvatar.trim());
        }
        if (StringUtils.hasText(tagsJson)) {
            values.put("tagsJson", tagsJson.trim());
        }
        if (publishTime != null) {
            values.put("publishTime", String.valueOf(publishTime));
        }
        if (likeCount != null) {
            values.put("likeCount", String.valueOf(likeCount));
        }
        if (favoriteCount != null) {
            values.put("favoriteCount", String.valueOf(favoriteCount));
        }
        redisTemplate.opsForHash().putAll(buildContentKey(type, id), values);
    }

    /**
     * 更新单个互动计数字段，并保证不会跌到 0 以下。
     *
     * @param contentKey 内容元数据 key
     * @param field 计数字段名
     * @param delta 增量
     */
    private void updateCounterField(String contentKey, String field, int delta) {
        Integer current = asInteger(redisTemplate.opsForHash().get(contentKey, field));
        int next = Math.max(0, (current == null ? 0 : current) + delta);
        redisTemplate.opsForHash().put(contentKey, field, String.valueOf(next));
    }

    /**
     * 判断当前增量是否非 0。
     *
     * @param delta 计数增量
     * @return 非 0 返回 true
     */
    private boolean hasDelta(int delta) {
        return delta != 0;
    }

    /**
     * 递增某个内容类型对应的缓存版本号。
     * 版本号变化后，旧缓存 key 会自然失效。
     *
     * @param type 内容类型
     */
    private void bumpCacheVersion(String type) {
        try {
            redisTemplate.opsForValue().increment(CACHE_VERSION_KEY_PREFIX + type);
        } catch (Exception ex) {
            log.warn("Failed to bump LBS cache version. type={}", type, ex);
        }
    }

    /**
     * 读取当前缓存版本号。
     * 不存在或读取失败时统一回退为 0。
     *
     * @param type 内容类型
     * @return 缓存版本号
     */
    private long resolveCacheVersion(String type) {
        try {
            String value = redisTemplate.opsForValue().get(CACHE_VERSION_KEY_PREFIX + type);
            return StringUtils.hasText(value) ? Long.parseLong(value) : 0L;
        } catch (Exception ex) {
            log.warn("Failed to resolve LBS cache version. type={}", type, ex);
            return 0L;
        }
    }

    /**
     * 构造空结果响应。
     * 用于无匹配结果或开启 fail-open 且查询失败时的统一兜底返回。
     *
     * @param request 原始搜索请求
     * @return 空搜索结果
     */
    private NearbySearchResponse emptyResponse(NearbySearchRequest request) {
        return new NearbySearchResponse(Collections.emptyList(), 0, request.page(), request.size());
    }

    /**
     * 构造内容元数据对应的 Redis key。
     *
     * @param type 内容类型
     * @param id 内容 ID
     * @return 元数据 Redis key
     */
    private String buildContentKey(String type, String id) {
        return CONTENT_KEY_PREFIX + type + ":" + id;
    }

    /**
     * 对内容类型做标准化处理。
     * 空值回退为默认类型，非空值会做 key 安全清洗。
     *
     * @param type 原始内容类型
     * @return 标准化后的类型值
     */
    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return discoverProperties.getDefaultType();
        }

        String normalized = sanitizeSegment(type);
        if ("post".equals(normalized) || "mixed".equals(normalized)) {
            return discoverProperties.getDefaultType();
        }
        return StringUtils.hasText(normalized) ? normalized : discoverProperties.getDefaultType();
    }

    /**
     * 灏嗗唴閮ㄥ瓨鍌ㄧ被鍨嬫槧灏勪负瀵瑰 API 绾﹀畾鐨勫疄浣撶被鍨嬨€?
     */
    private String externalType(String normalizedType) {
        if (discoverProperties.getDefaultType().equals(normalizedType)) {
            return "post";
        }
        return normalizedType;
    }

    /**
     * 根据搜索半径选择 GeoHash 精度。
     * 半径越小，精度越高；半径越大，分桶越粗，以平衡命中率和 key 数量。
     *
     * @param radiusMeters 搜索半径，单位米
     * @return GeoHash 精度长度
     */
    private int resolveGeoHashPrecision(Integer radiusMeters) {
        if (radiusMeters == null) {
            return 6;
        }
        if (radiusMeters <= 500) {
            return 7;
        }
        if (radiusMeters <= 2_000) {
            return 6;
        }
        if (radiusMeters <= 10_000) {
            return 5;
        }
        return 4;
    }

    /**
     * 构造 Redis Geo 查询参数。
     * 会要求返回距离和坐标，按距离升序排序，并为大范围查询设置结果上限。
     *
     * @param request 搜索请求
     * @return Geo 查询参数对象
     */
    private GeoRadiusCommandArgs buildSearchArgs(NearbySearchRequest request) {
        return GeoRadiusCommandArgs.newGeoRadiusArgs()
            .includeDistance()
            .includeCoordinates()
            .sortAscending()
            .limit(calculateRedisCount(request));
    }

    /**
     * 计算单次 Redis Geo 查询允许返回的最大候选数。
     * 会基于当前页窗口额外补一个分页缓冲，并受全局上限约束。
     *
     * @param request 搜索请求
     * @return Redis COUNT 参数值
     */
    private int calculateRedisCount(NearbySearchRequest request) {
        long requestedWindow = Math.max(1L, (long) request.page() * request.size());
        long bufferedWindow = requestedWindow + request.size();
        return Math.min(safeToInt(bufferedWindow), discoverProperties.getMaxRedisFetchCount());
    }

    /**
     * 计算分页起始下标。
     * 通过 long 作为中间值避免 page 与 size 相乘时发生 int 溢出。
     *
     * @param page 页码
     * @param size 每页大小
     * @param total 总数
     * @return 起始下标
     */
    private int calculateFromIndex(Integer page, Integer size, int total) {
        long offset = Math.max(0L, ((long) page - 1L) * (long) size);
        return Math.min(safeToInt(offset), total);
    }

    /**
     * 计算分页结束下标。
     * 结束下标为开区间值，不会超过当前候选结果总数。
     *
     * @param fromIndex 起始下标
     * @param size 每页大小
     * @param total 总数
     * @return 结束下标（不含）
     */
    private int calculateToIndex(int fromIndex, Integer size, int total) {
        long endExclusive = (long) fromIndex + size;
        return Math.min(safeToInt(endExclusive), total);
    }

    /**
     * 将 long 安全收缩为 int。
     * 负数统一归零，超过 int 上限时截断为最大值。
     *
     * @param value long 值
     * @return 安全转换后的 int 值
     */
    private int safeToInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * 对搜索请求做服务层保护性校验。
     * 即使控制器层已使用校验注解，这里仍会再次验证关键参数范围。
     *
     * @param request 搜索请求
     */
    private void validateSearchRequest(NearbySearchRequest request) {
        if (request == null) {
            throw badRequest("search request must not be null");
        }

        validateCoordinate("lat", request.lat(), -90.0, 90.0);
        validateCoordinate("lng", request.lng(), -180.0, 180.0);
        validateIntegerRange("radius", request.radius(), discoverProperties.getMinRadiusMeters(), discoverProperties.getMaxRadiusMeters());
        validateIntegerRange("page", request.page(), 1, Integer.MAX_VALUE);
        validateIntegerRange("size", request.size(), 1, discoverProperties.getMaxPageSize());
    }

    /**
     * 校验坐标字段是否在合法范围内。
     *
     * @param field 字段名
     * @param value 数值
     * @param min 最小值
     * @param max 最大值
     */
    private void validateCoordinate(String field, Double value, double min, double max) {
        if (value == null || value < min || value > max) {
            throw badRequest(field + " is out of range");
        }
    }

    /**
     * 校验整数参数范围。
     *
     * @param field 字段名
     * @param value 数值
     * @param min 最小值
     * @param max 最大值
     */
    private void validateIntegerRange(String field, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            throw badRequest(field + " is out of range");
        }
    }

    /**
     * 构造统一的 400 业务异常。
     *
     * @param message 错误消息
     * @return 业务异常对象
     */
    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 尝试获取缓存回源锁。
     * 锁值使用 UUID，并带有过期时间，避免异常情况下产生死锁。
     *
     * @param lockKey 锁 key
     * @param lockValue 锁值
     * @return 获取成功返回 true，否则返回 false
     */
    private boolean tryLock(String lockKey, String lockValue) {
        try {
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    lockValue,
                    Duration.ofSeconds(discoverProperties.getLockTtlSeconds())
            );
            return Boolean.TRUE.equals(locked);
        } catch (Exception ex) {
            log.warn("Failed to acquire LBS cache lock. key={}", lockKey, ex);
            return false;
        }
    }

    /**
     * 安全释放分布式锁。
     * 通过 Lua 脚本先比对锁值再删除，避免误删其他线程持有的锁。
     *
     * @param lockKey 锁 key
     * @param lockValue 锁值
     */
    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception ex) {
            log.warn("Failed to release LBS cache lock. key={}", lockKey, ex);
        }
    }

    /**
     * 在锁等待期间短暂休眠。
     * 线程被中断时会恢复中断标记并抛出业务异常。
     *
     * @param duration 休眠时长
     */
    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "search interrupted");
        }
    }

    /**
     * 将纬度和经度转换为 Redis Point。
     * Redis Point 约定 x 为经度、y 为纬度，这里统一封装以避免顺序混淆。
     *
     * @param lat 纬度
     * @param lng 经度
     * @return Redis 点对象
     */
    private Point toRedisPoint(Double lat, Double lng) {
        return new Point(lng, lat);
    }

    /**
     * 解析最终返回给前端的纬度。
     * 优先使用 Geo 查询返回坐标，不存在时回退到元数据中的备用坐标。
     *
     * @param point Geo 查询返回点
     * @param metadata 元数据对象
     * @return 纬度
     */
    private Double resolveLatitude(Point point, LbsContentMetadata metadata) {
        return point != null ? point.getY() : metadata.lat();
    }

    /**
     * 解析最终返回给前端的经度。
     * 优先使用 Geo 查询返回坐标，不存在时回退到元数据中的备用坐标。
     *
     * @param point Geo 查询返回点
     * @param metadata 元数据对象
     * @return 经度
     */
    private Double resolveLongitude(Point point, LbsContentMetadata metadata) {
        return point != null ? point.getX() : metadata.lng();
    }

    /**
     * 对 Redis key 片段做安全清洗。
     * 仅保留小写字母、数字、下划线和短横线，其余字符统一替换为下划线。
     *
     * @param value 原始片段值
     * @return 清洗后的片段
     */
    private String sanitizeSegment(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    /**
     * 对原始缓存 key 做 SHA-256 哈希。
     * 用于压缩长度并减少敏感参数在 Redis key 中的直接暴露。
     *
     * @param rawKey 原始缓存 key 字符串
     * @return 十六进制哈希串
     */
    private String hashCacheKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * 将任意对象安全转换为字符串。
     * 兼容普通对象和 byte[]，空白字符串会被统一视为 null。
     *
     * @param value 原始值
     * @return 转换后的字符串
     */
    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 解析 Pipeline 返回的单条元数据对象。
     * 支持从 Map 中提取标题、发布时间、点赞数和备用坐标。
     *
     * @param rawMetadata 原始元数据对象
     * @return 解析后的元数据对象
     */
    private LbsContentMetadata parseMetadata(Object rawMetadata) {
        if (!(rawMetadata instanceof Map<?, ?> values) || values.isEmpty()) {
            return LbsContentMetadata.empty();
        }

        String title = null;
        String summary = null;
        String coverUrl = null;
        String address = null;
        String authorId = null;
        String authorName = null;
        String authorAvatar = null;
        List<String> tags = Collections.emptyList();
        Long publishTime = null;
        Integer likeCount = null;
        Integer favoriteCount = null;
        Double lat = null;
        Double lng = null;

        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = asString(entry.getKey());
            if (key == null) {
                continue;
            }

            if ("title".equals(key)) {
                title = asString(entry.getValue());
            } else if ("summary".equals(key)) {
                summary = asString(entry.getValue());
            } else if ("coverUrl".equals(key)) {
                coverUrl = asString(entry.getValue());
            } else if ("address".equals(key)) {
                address = asString(entry.getValue());
            } else if ("authorId".equals(key)) {
                authorId = asString(entry.getValue());
            } else if ("authorName".equals(key)) {
                authorName = asString(entry.getValue());
            } else if ("authorAvatar".equals(key)) {
                authorAvatar = asString(entry.getValue());
            } else if ("tagsJson".equals(key)) {
                tags = parseTags(entry.getValue());
            } else if ("publishTime".equals(key)) {
                publishTime = asLong(entry.getValue());
            } else if ("likeCount".equals(key)) {
                likeCount = asInteger(entry.getValue());
            } else if ("favoriteCount".equals(key)) {
                favoriteCount = asInteger(entry.getValue());
            } else if ("lat".equals(key)) {
                lat = asDouble(entry.getValue());
            } else if ("lng".equals(key)) {
                lng = asDouble(entry.getValue());
            }
        }

        return new LbsContentMetadata(title, summary, coverUrl, address, tags, authorId, authorName, authorAvatar, publishTime, likeCount, favoriteCount, lat, lng);
    }

    /**
     * 解析标签 JSON。
     */
    private List<String> parseTags(Object value) {
        String raw = asString(value);
        if (!StringUtils.hasText(raw)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(raw, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            log.debug("Failed to parse discover tags json: {}", raw, ex);
            return Collections.emptyList();
        }
    }

    /**
     * 标准化标签过滤条件。
     */
    private String normalizeOptionalTag(String tag) {
        if (!StringUtils.hasText(tag)) {
            return null;
        }
        String normalized = sanitizeSegment(tag);
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    /**
     * 判断当前结果是否命中标签过滤。
     */
    private boolean matchesTag(String normalizedTag, List<String> tags) {
        if (!StringUtils.hasText(normalizedTag)) {
            return true;
        }
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (normalizedTag.equals(normalizeOptionalTag(tag))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将对象安全解析为 Long。
     *
     * @param value 原始值
     * @return Long 值，失败时返回 null
     */
    private Long asLong(Object value) {
        return safeParse(value, Long::parseLong);
    }

    /**
     * 将对象安全解析为 Integer。
     *
     * @param value 原始值
     * @return Integer 值，失败时返回 null
     */
    private Integer asInteger(Object value) {
        return safeParse(value, Integer::parseInt);
    }

    /**
     * 将对象安全解析为 Double。
     *
     * @param value 原始值
     * @return Double 值，失败时返回 null
     */
    private Double asDouble(Object value) {
        return safeParse(value, Double::parseDouble);
    }

    /**
     * 通用安全解析方法。
     * 会先转成字符串，再调用指定解析器；任何运行时异常都返回 null。
     *
     * @param value 原始值
     * @param parser 解析函数
     * @param <T> 目标类型
     * @return 解析结果
     */
    private <T> T safeParse(Object value, Function<String, T> parser) {
        try {
            String text = asString(value);
            return text == null ? null : parser.apply(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 搜索结果内部使用的元数据对象。
     * 用于承载标题、发布时间、点赞数以及备用坐标等字段。
     */
    private static final class LbsContentMetadata {
        private final String title;
        private final String summary;
        private final String coverUrl;
        private final String address;
        private final List<String> tags;
        private final String authorId;
        private final String authorName;
        private final String authorAvatar;
        private final Long publishTime;
        private final Integer likeCount;
        private final Integer favoriteCount;
        private final Double lat;
        private final Double lng;

        /**
         * 构造元数据对象。
         *
         * @param title 标题
         * @param publishTime 发布时间
         * @param likeCount 点赞数
         * @param lat 纬度
         * @param lng 经度
         */
        private LbsContentMetadata(
                String title,
                String summary,
                String coverUrl,
                String address,
                List<String> tags,
                String authorId,
                String authorName,
                String authorAvatar,
                Long publishTime,
                Integer likeCount,
                Integer favoriteCount,
                Double lat,
                Double lng
        ) {
            this.title = title;
            this.summary = summary;
            this.coverUrl = coverUrl;
            this.address = address;
            this.tags = tags;
            this.authorId = authorId;
            this.authorName = authorName;
            this.authorAvatar = authorAvatar;
            this.publishTime = publishTime;
            this.likeCount = likeCount;
            this.favoriteCount = favoriteCount;
            this.lat = lat;
            this.lng = lng;
        }

        /**
         * 创建空元数据对象。
         *
         * @return 空元数据实例
         */
        private static LbsContentMetadata empty() {
            return new LbsContentMetadata(null, null, null, null, Collections.emptyList(), null, null, null, null, null, null, null, null);
        }

        /**
         * 获取标题。
         *
         * @return 标题
         */
        private String title() {
            return title;
        }

        private String summary() {
            return summary;
        }

        private String coverUrl() {
            return coverUrl;
        }

        private String address() {
            return address;
        }

        private List<String> tags() {
            return tags;
        }

        private String authorId() {
            return authorId;
        }

        private String authorName() {
            return authorName;
        }

        private String authorAvatar() {
            return authorAvatar;
        }

        /**
         * 获取发布时间。
         *
         * @return 发布时间时间戳
         */
        private Long publishTime() {
            return publishTime;
        }

        /**
         * 获取点赞数。
         *
         * @return 点赞数
         */
        private Integer likeCount() {
            return likeCount;
        }

        private Integer favoriteCount() {
            return favoriteCount;
        }

        /**
         * 获取纬度。
         *
         * @return 纬度
         */
        private Double lat() {
            return lat;
        }

        /**
         * 获取经度。
         *
         * @return 经度
         */
        private Double lng() {
            return lng;
        }
    }
}
