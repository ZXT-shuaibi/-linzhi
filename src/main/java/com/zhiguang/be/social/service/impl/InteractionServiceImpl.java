package com.zhiguang.be.social.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.common.tx.Transactions;
import com.zhiguang.be.common.util.Numbers;
import com.zhiguang.be.discover.service.LbsDiscoverService;
import com.zhiguang.be.feed.service.FeedCacheInvalidationService;
import com.zhiguang.be.social.CounterEventPayload;
import com.zhiguang.be.social.SocialCounterSchema;
import com.zhiguang.be.social.InteractionActionData;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.PostTargetSnapshot;
import com.zhiguang.be.social.SocialRedisKeys;
import com.zhiguang.be.social.kafka.CounterEvent;
import com.zhiguang.be.social.kafka.CounterEventProducer;
import com.zhiguang.be.social.mapper.SocialMapper;
import com.zhiguang.be.social.service.CounterAggregationOperations;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.InteractionService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 社交互动服务实现。
 * 负责点赞、收藏、互动汇总、位图缓存和实体计数 SDS 的维护。
 */
@Service
public class InteractionServiceImpl implements InteractionService, CounterAggregationOperations {

    private static final Logger log = LoggerFactory.getLogger(InteractionServiceImpl.class);
    private static final String DISCOVER_TYPE = "knowledge";

    private static final int OFFSET_LIKE = SocialCounterSchema.offsetOf(SocialCounterSchema.IDX_LIKE);
    private static final int OFFSET_FAVORITE = SocialCounterSchema.offsetOf(SocialCounterSchema.IDX_FAV);
    private static final String AGGREGATE_FIELD_LIKE = String.valueOf(SocialCounterSchema.IDX_LIKE);
    private static final String AGGREGATE_FIELD_FAVORITE = String.valueOf(SocialCounterSchema.IDX_FAV);

    private final SocialMapper socialMapper;
    private final FollowService followService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final UserSocialCounterService userSocialCounterService;
    private final CounterEventProducer counterEventProducer;
    private final LbsDiscoverService lbsDiscoverService;
    private final FeedCacheInvalidationService feedCacheInvalidationService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> bitmapToggleScript;
    private final DefaultRedisScript<Long> entityCounterIncrementScript;
    private final DefaultRedisScript<String> aggregateFoldScript;
    private final DefaultRedisScript<Long> rebuildRateLimitScript;
    private final DefaultRedisScript<Long> rebuildLockReleaseScript;

    @Value("${social.counter.rebuild.lock-ttl-ms:5000}")
    private long rebuildLockTtlMs;
    @Value("${social.counter.rebuild.rate-permits:3}")
    private int rebuildRatePermits;
    @Value("${social.counter.rebuild.rate-window-seconds:10}")
    private int rebuildRateWindowSeconds;
    @Value("${social.counter.rebuild.backoff-base-ms:500}")
    private long rebuildBackoffBaseMs;
    @Value("${social.counter.rebuild.backoff.max-ms:30000}")
    private long rebuildBackoffMaxMs;

    /**
     * 构造互动服务。
     *
     * @param socialMapper 社交模块 Mapper
     * @param snowflakeIdGenerator 雪花 ID 生成器
     * @param userSocialCounterService 用户维社交计数服务
     * @param stringRedisTemplate Redis 模板
     * @param objectMapper JSON 序列化组件
     */
    public InteractionServiceImpl(
            SocialMapper socialMapper,
            FollowService followService,
            SnowflakeIdGenerator snowflakeIdGenerator,
            UserSocialCounterService userSocialCounterService,
            CounterEventProducer counterEventProducer,
            ObjectProvider<LbsDiscoverService> lbsDiscoverServiceProvider,
            ObjectProvider<FeedCacheInvalidationService> feedCacheInvalidationServiceProvider,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper
    ) {
        this.socialMapper = socialMapper;
        this.followService = followService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.userSocialCounterService = userSocialCounterService;
        this.counterEventProducer = counterEventProducer;
        this.lbsDiscoverService = lbsDiscoverServiceProvider.getIfAvailable();
        this.feedCacheInvalidationService = feedCacheInvalidationServiceProvider.getIfAvailable();
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;

        this.bitmapToggleScript = new DefaultRedisScript<Long>();
        this.bitmapToggleScript.setResultType(Long.class);
        this.bitmapToggleScript.setScriptText(
                "local bmKey = KEYS[1]\n"
                        + "local offset = tonumber(ARGV[1])\n"
                        + "local op = ARGV[2]\n"
                        + "local prev = redis.call('GETBIT', bmKey, offset)\n"
                        + "if op == 'add' then\n"
                        + "  if prev == 1 then return 0 end\n"
                        + "  redis.call('SETBIT', bmKey, offset, 1)\n"
                        + "  return 1\n"
                        + "elseif op == 'remove' then\n"
                        + "  if prev == 0 then return 0 end\n"
                        + "  redis.call('SETBIT', bmKey, offset, 0)\n"
                        + "  return 1\n"
                        + "end\n"
                        + "return -1\n"
        );

        this.entityCounterIncrementScript = new DefaultRedisScript<Long>();
        this.entityCounterIncrementScript.setResultType(Long.class);
        this.entityCounterIncrementScript.setScriptText(
                "local cntKey = KEYS[1]\n"
                        + "local schemaLen = tonumber(ARGV[1])\n"
                        + "local fieldSize = tonumber(ARGV[2])\n"
                        + "local idx = tonumber(ARGV[3])\n"
                        + "local delta = tonumber(ARGV[4])\n"
                        + "local function read32be(s, off)\n"
                        + "  local b = {string.byte(s, off + 1, off + 4)}\n"
                        + "  local n = 0\n"
                        + "  for i = 1, 4 do n = n * 256 + b[i] end\n"
                        + "  return n\n"
                        + "end\n"
                        + "local function write32be(n)\n"
                        + "  local t = {}\n"
                        + "  for i = 4, 1, -1 do t[i] = n % 256; n = math.floor(n / 256) end\n"
                        + "  return string.char(unpack(t))\n"
                        + "end\n"
                        + "local cnt = redis.call('GET', cntKey)\n"
                        + "if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end\n"
                        + "local off = idx * fieldSize\n"
                        + "local v = read32be(cnt, off) + delta\n"
                        + "if v < 0 then v = 0 end\n"
                        + "local seg = write32be(v)\n"
                        + "cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off + fieldSize + 1)\n"
                        + "redis.call('SET', cntKey, cnt)\n"
                        + "return 1\n"
        );

        this.aggregateFoldScript = new DefaultRedisScript<String>();
        this.aggregateFoldScript.setResultType(String.class);
        this.aggregateFoldScript.setScriptText(
                "local aggKey = KEYS[1]\n"
                        + "local cntKey = KEYS[2]\n"
                        + "local schemaLen = tonumber(ARGV[1])\n"
                        + "local fieldSize = tonumber(ARGV[2])\n"
                        + "local likeField = ARGV[3]\n"
                        + "local favoriteField = ARGV[4]\n"
                        + "local function read32be(s, off)\n"
                        + "  local b = {string.byte(s, off + 1, off + 4)}\n"
                        + "  local n = 0\n"
                        + "  for i = 1, 4 do n = n * 256 + b[i] end\n"
                        + "  return n\n"
                        + "end\n"
                        + "local function write32be(n)\n"
                        + "  local t = {}\n"
                        + "  for i = 4, 1, -1 do t[i] = n % 256; n = math.floor(n / 256) end\n"
                        + "  return string.char(unpack(t))\n"
                        + "end\n"
                        + "local function writeValue(cnt, off, value)\n"
                        + "  local seg = write32be(value)\n"
                        + "  return string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off + fieldSize + 1)\n"
                        + "end\n"
                        + "local likeDelta = tonumber(redis.call('HGET', aggKey, likeField) or '0')\n"
                        + "local favoriteDelta = tonumber(redis.call('HGET', aggKey, favoriteField) or '0')\n"
                        + "if likeDelta == 0 and favoriteDelta == 0 then return '0,0' end\n"
                        + "local cnt = redis.call('GET', cntKey)\n"
                        + "if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end\n"
                        + "local likeValue = read32be(cnt, fieldSize) + likeDelta\n"
                        + "if likeValue < 0 then likeValue = 0 end\n"
                        + "cnt = writeValue(cnt, fieldSize, likeValue)\n"
                        + "local favoriteValue = read32be(cnt, fieldSize * 2) + favoriteDelta\n"
                        + "if favoriteValue < 0 then favoriteValue = 0 end\n"
                        + "cnt = writeValue(cnt, fieldSize * 2, favoriteValue)\n"
                        + "redis.call('SET', cntKey, cnt)\n"
                        + "redis.call('DEL', aggKey)\n"
                        + "return tostring(likeDelta) .. ',' .. tostring(favoriteDelta)\n"
        );

        this.rebuildRateLimitScript = new DefaultRedisScript<Long>();
        this.rebuildRateLimitScript.setResultType(Long.class);
        this.rebuildRateLimitScript.setScriptText(
                "local rateKey = KEYS[1]\n"
                        + "local permits = tonumber(ARGV[1])\n"
                        + "local windowMs = tonumber(ARGV[2])\n"
                        + "local current = redis.call('INCR', rateKey)\n"
                        + "if current == 1 then redis.call('PEXPIRE', rateKey, windowMs) end\n"
                        + "if current <= permits then return 1 end\n"
                        + "return 0\n"
        );

        this.rebuildLockReleaseScript = new DefaultRedisScript<Long>();
        this.rebuildLockReleaseScript.setResultType(Long.class);
        this.rebuildLockReleaseScript.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
                        + "  return redis.call('DEL', KEYS[1])\n"
                        + "end\n"
                        + "return 0\n"
        );
    }

    /**
     * 对目标内容执行点赞。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 动作结果
     */
    @Override
    @Transactional
    public InteractionActionData like(long currentUserId, String targetType, long targetId) {
        return changeInteraction(currentUserId, targetType, targetId, "like", true);
    }

    /**
     * 对目标内容取消点赞。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 动作结果
     */
    @Override
    @Transactional
    public InteractionActionData unlike(long currentUserId, String targetType, long targetId) {
        return changeInteraction(currentUserId, targetType, targetId, "like", false);
    }

    /**
     * 对目标内容执行收藏。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 动作结果
     */
    @Override
    @Transactional
    public InteractionActionData favorite(long currentUserId, String targetType, long targetId) {
        return changeInteraction(currentUserId, targetType, targetId, "favorite", true);
    }

    /**
     * 对目标内容取消收藏。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 动作结果
     */
    @Override
    @Transactional
    public InteractionActionData unfavorite(long currentUserId, String targetType, long targetId) {
        return changeInteraction(currentUserId, targetType, targetId, "favorite", false);
    }

    /**
     * 查询单个目标内容的互动汇总。
     *
     * @param currentUserId 当前查看者用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 互动汇总
     */
    @Override
    public InteractionSummary summary(long currentUserId, String targetType, long targetId) {
        loadTargetSnapshot(currentUserId, targetType, targetId);
        long[] stats = readEntityCounters(targetType, targetId);
        boolean viewerLiked = currentUserId > 0L && isInteractionActive(currentUserId, targetType, targetId, "like");
        boolean viewerFavorited = currentUserId > 0L && isInteractionActive(currentUserId, targetType, targetId, "favorite");
        return new InteractionSummary(
                targetType,
                String.valueOf(targetId),
                stats[0],
                stats[1],
                viewerLiked,
                viewerFavorited
        );
    }

    /**
     * 批量查询多个目标内容的互动汇总。
     *
     * @param currentUserId 当前查看者用户 ID
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表
     * @return 以目标 ID 为键的互动汇总映射
     */
    @Override
    public Map<String, InteractionSummary> summaryBatch(long currentUserId, String targetType, List<Long> targetIds) {
        Map<String, InteractionSummary> result = new LinkedHashMap<String, InteractionSummary>();
        List<Long> normalizedTargetIds = normalizeTargetIds(targetIds);
        if (normalizedTargetIds.isEmpty()) {
            return result;
        }

        ensureSupportedTargetType(targetType);
        Map<Long, PostTargetSnapshot> snapshots = loadTargetSnapshots(currentUserId, targetType, normalizedTargetIds);
        Map<Long, long[]> counters = readEntityCountersBatch(targetType, normalizedTargetIds);
        Map<Long, boolean[]> viewerStates = readViewerStatesBatch(currentUserId, targetType, normalizedTargetIds);

        for (Long targetId : normalizedTargetIds) {
            PostTargetSnapshot snapshot = snapshots.get(targetId);
            if (snapshot == null) {
                continue;
            }

            long[] stats = counters.get(targetId);
            if (stats == null) {
                stats = new long[]{0L, 0L};
            }

            boolean[] states = viewerStates.get(targetId);
            boolean viewerLiked = states != null && states[0];
            boolean viewerFavorited = states != null && states[1];

            result.put(
                    String.valueOf(targetId),
                    new InteractionSummary(
                            targetType,
                            String.valueOf(targetId),
                            stats[0],
                            stats[1],
                            viewerLiked,
                            viewerFavorited
                    )
            );
        }
        return result;
    }

    /**
     * 统一处理点赞或收藏状态切换。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @param active true 表示生效，false 表示取消
     * @return 动作结果
     */
    private InteractionActionData changeInteraction(
            long currentUserId,
            String targetType,
            long targetId,
            String action,
            boolean active
    ) {
        SocialServiceSupport.ensureAuthenticatedUser(currentUserId);
        PostTargetSnapshot snapshot = loadTargetSnapshot(currentUserId, targetType, targetId);
        boolean changed = active
                ? activateInteractionState(currentUserId, targetType, targetId, action)
                : deactivateInteractionState(currentUserId, targetType, targetId, action);

        if (!changed) {
            return buildActionData(targetType, targetId, action, active, false);
        }

        int delta = active ? 1 : -1;
        String eventType = resolveEventType(action);
        long eventId = snowflakeIdGenerator.nextId();
        socialMapper.insertOutboxEvent(
                eventId,
                "interaction",
                targetId,
                eventType,
                SocialServiceSupport.serialize(objectMapper, CounterEventPayload.of(eventId, eventType, targetType, targetId, action, currentUserId, delta))
        );

        Transactions.runAfterCommit(() -> {
            try {
                syncBitmap(targetType, targetId, currentUserId, action, active);
                CounterEvent counterEvent = CounterEvent.of(
                        targetType,
                        String.valueOf(targetId),
                        resolveBitmapMetric(action),
                        resolveCounterFieldIndex(action),
                        currentUserId,
                        delta
                );
                if (!counterEventProducer.isEnabled() || !counterEventProducer.publish(counterEvent)) {
                    incrementAggregateBucket(targetType, targetId, action, delta);
                }
                invalidateFeedCache(targetType, targetId);
                if ("like".equals(action)) {
                    userSocialCounterService.incrementLikesReceived(snapshot.getCreatorId(), delta);
                } else {
                    userSocialCounterService.incrementFavoritesReceived(snapshot.getCreatorId(), delta);
                }
                refreshDiscoverInteractionStats(snapshot, targetType, targetId, action, delta);
            } catch (Exception ex) {
                log.warn(
                        "refresh interaction cache failed, userId={}, targetType={}, targetId={}, action={}, active={}",
                        currentUserId,
                        targetType,
                        targetId,
                        action,
                        active,
                        ex
                );
            }
        });

        return buildActionData(targetType, targetId, action, active, true);
    }

    /**
     * 将互动增量同步到 discover 元数据。
     * discover 目前只承接公开已发布内容，因此这里对非公开内容直接跳过。
     *
     * @param snapshot 内容快照
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 互动动作
     * @param delta 增量
     */
    private void refreshDiscoverInteractionStats(
            PostTargetSnapshot snapshot,
            String targetType,
            long targetId,
            String action,
            int delta
    ) {
        if (lbsDiscoverService == null || snapshot == null || delta == 0) {
            return;
        }
        if (!"post".equalsIgnoreCase(targetType) || !snapshot.interactable() || !snapshot.isPublicVisible()) {
            return;
        }

        int likeDelta = "like".equals(action) ? delta : 0;
        int favoriteDelta = "favorite".equals(action) ? delta : 0;
        lbsDiscoverService.incrementInteractionStats(
                String.valueOf(targetId),
                DISCOVER_TYPE,
                likeDelta,
                favoriteDelta
        );
    }

    /**
     * 构造互动动作返回结果。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @param active 当前动作是否生效
     * @return 动作结果
     */
    private InteractionActionData buildActionData(String targetType, long targetId, String action, boolean active, boolean changed) {
        return new InteractionActionData(
                targetType,
                String.valueOf(targetId),
                action,
                active,
                changed,
                Math.toIntExact(Instant.now().getEpochSecond())
        );
    }

    private void invalidateFeedCache(String targetType, long targetId) {
        if (feedCacheInvalidationService == null || !"post".equalsIgnoreCase(targetType)) {
            return;
        }
        feedCacheInvalidationService.invalidatePostAfterCommit(String.valueOf(targetId));
    }

    /**
     * 规范化批量查询目标 ID。
     *
     * @param targetIds 原始目标 ID 列表
     * @return 去重且有效的目标 ID 列表
     */
    private List<Long> normalizeTargetIds(List<Long> targetIds) {
        List<Long> normalized = new ArrayList<Long>();
        if (targetIds == null || targetIds.isEmpty()) {
            return normalized;
        }

        Map<Long, Boolean> unique = new LinkedHashMap<Long, Boolean>();
        for (Long targetId : targetIds) {
            if (targetId == null || targetId <= 0L) {
                continue;
            }
            if (!unique.containsKey(targetId)) {
                unique.put(targetId, Boolean.TRUE);
            }
        }
        normalized.addAll(unique.keySet());
        return normalized;
    }

    /**
     * 批量加载内容快照并校验是否允许互动。
     *
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表
     * @return 以目标 ID 为键的快照映射
     */
    private Map<Long, PostTargetSnapshot> loadTargetSnapshots(long currentUserId, String targetType, List<Long> targetIds) {
        Map<Long, PostTargetSnapshot> snapshots = new LinkedHashMap<Long, PostTargetSnapshot>();
        if (targetIds.isEmpty()) {
            return snapshots;
        }

        List<Map<String, Object>> rows = socialMapper.listPostSnapshotsByIds(targetIds);
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                PostTargetSnapshot snapshot = toSnapshot(row);
                if (snapshot != null) {
                    snapshots.put(snapshot.getPostId(), snapshot);
                }
            }
        }

        for (Long targetId : targetIds) {
            PostTargetSnapshot snapshot = snapshots.get(targetId);
            if (snapshot == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "目标内容不存在");
            }
            ensureSnapshotAccessible(currentUserId, snapshot);
            if (!snapshot.interactable()) {
                throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "当前内容不可互动");
            }
        }
        return snapshots;
    }

    /**
     * 将数据库行转换为内容快照。
     *
     * @param row 数据库查询结果
     * @return 内容快照
     */
    private PostTargetSnapshot toSnapshot(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object postId = row.get("postId");
        Object creatorId = row.get("creatorId");
        if (postId == null || creatorId == null) {
            return null;
        }
        return new PostTargetSnapshot(
                Numbers.toLong(postId),
                Numbers.toLong(creatorId),
                SocialServiceSupport.toStringValue(row.get("status")),
                SocialServiceSupport.toStringValue(row.get("visible"))
        );
    }

    /**
     * 批量读取实体计数。
     * 优先读取 Redis SDS，缺失时回退数据库聚合。
     *
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表
     * @return 以目标 ID 为键的计数映射
     */
    private Map<Long, long[]> readEntityCountersBatch(String targetType, List<Long> targetIds) {
        Map<Long, long[]> result = new LinkedHashMap<Long, long[]>();
        if (targetIds.isEmpty()) {
            return result;
        }

        List<String> keys = new ArrayList<String>(targetIds.size());
        for (Long targetId : targetIds) {
            keys.add(SocialRedisKeys.entityCounterKey(targetType, targetId));
        }

        List<Object> pipelineResult = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        List<Long> rebuildTargetIds = new ArrayList<Long>();
        for (int i = 0; i < targetIds.size(); i++) {
            Long targetId = targetIds.get(i);
            Object value = pipelineResult != null && i < pipelineResult.size() ? pipelineResult.get(i) : null;
            byte[] raw = value instanceof byte[] ? (byte[]) value : null;
            if (isValidEntityCounterRaw(raw)) {
                result.put(targetId, new long[]{
                        readInt32BE(raw, OFFSET_LIKE),
                        readInt32BE(raw, OFFSET_FAVORITE)
                });
            } else {
                result.put(targetId, new long[]{0L, 0L});
                rebuildTargetIds.add(targetId);
            }
        }

        List<Long> fallbackTargetIds = new ArrayList<Long>();
        for (Long targetId : rebuildTargetIds) {
            long[] rebuilt = tryRebuildEntityCounter(targetType, targetId);
            if (rebuilt != null) {
                result.put(targetId, rebuilt);
            } else {
                fallbackTargetIds.add(targetId);
            }
        }

        if (!fallbackTargetIds.isEmpty()) {
            List<Map<String, Object>> rows = socialMapper.aggregateActiveInteractionCountsBatch(targetType, fallbackTargetIds);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    long targetId = Numbers.toLong(row.get("targetId"));
                    String actionType = SocialServiceSupport.toStringValue(row.get("actionType"));
                    long total = Numbers.toLong(row.get("total"));
                    long[] stats = result.get(targetId);
                    if (stats == null) {
                        stats = new long[]{0L, 0L};
                        result.put(targetId, stats);
                    }
                    if ("like".equals(actionType)) {
                        stats[0] = total;
                    } else if ("favorite".equals(actionType)) {
                        stats[1] = total;
                    }
                }
            }
            for (Long targetId : fallbackTargetIds) {
                long[] stats = result.get(targetId);
                if (stats == null) {
                    stats = new long[]{0L, 0L};
                    result.put(targetId, stats);
                }
                writeEntityCounterSnapshot(targetType, targetId, stats[0], stats[1]);
                clearAggregateBucket(targetType, targetId);
            }
        }

        Map<Long, long[]> aggregateDeltas = readAggregateDeltasBatch(targetType, targetIds);
        for (Long targetId : targetIds) {
            long[] stats = result.get(targetId);
            if (stats == null) {
                stats = new long[]{0L, 0L};
                result.put(targetId, stats);
            }
            long[] deltas = aggregateDeltas.get(targetId);
            if (deltas != null) {
                stats[0] += deltas[0];
                stats[1] += deltas[1];
            }
        }
        return result;
    }

    /**
     * 批量读取查看者对目标列表的点赞和收藏状态。
     * 优先读取 Redis 位图，缺失时回退数据库事实表。
     *
     * @param currentUserId 当前查看者用户 ID
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表
     * @return 以目标 ID 为键的状态映射，下标 0 为点赞，下标 1 为收藏
     */
    private Map<Long, boolean[]> readViewerStatesBatch(long currentUserId, String targetType, List<Long> targetIds) {
        Map<Long, boolean[]> result = new LinkedHashMap<Long, boolean[]>();
        for (Long targetId : targetIds) {
            result.put(targetId, new boolean[]{false, false});
        }
        if (currentUserId <= 0L || targetIds.isEmpty()) {
            return result;
        }

        List<String> keys = new ArrayList<String>(targetIds.size() * 2);
        List<Long> orderedTargetIds = new ArrayList<Long>(targetIds.size() * 2);
        List<String> orderedActions = new ArrayList<String>(targetIds.size() * 2);
        long bitOffset = SocialRedisKeys.bitOffsetOf(currentUserId);
        long chunk = SocialRedisKeys.chunkOf(currentUserId);

        for (Long targetId : targetIds) {
            keys.add(SocialRedisKeys.bitmapKey("like", targetType, targetId, chunk));
            orderedTargetIds.add(targetId);
            orderedActions.add("like");

            keys.add(SocialRedisKeys.bitmapKey("fav", targetType, targetId, chunk));
            orderedTargetIds.add(targetId);
            orderedActions.add("favorite");
        }

        List<Object> pipelineResult = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.stringCommands().getBit(key.getBytes(StandardCharsets.UTF_8), bitOffset);
            }
            return null;
        });

        for (int i = 0; i < orderedTargetIds.size(); i++) {
            Object value = pipelineResult != null && i < pipelineResult.size() ? pipelineResult.get(i) : null;
            if (!toBooleanResult(value)) {
                continue;
            }
            boolean[] states = result.get(orderedTargetIds.get(i));
            if (states == null) {
                continue;
            }
            if ("like".equals(orderedActions.get(i))) {
                states[0] = true;
            } else {
                states[1] = true;
            }
        }

        List<Map<String, Object>> rows = socialMapper.listActiveInteractionsByUserAndTargets(currentUserId, targetType, targetIds);
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                long targetId = Numbers.toLong(row.get("targetId"));
                String actionType = SocialServiceSupport.toStringValue(row.get("actionType"));
                boolean[] states = result.get(targetId);
                if (states == null) {
                    continue;
                }
                if ("like".equals(actionType)) {
                    states[0] = true;
                } else if ("favorite".equals(actionType)) {
                    states[1] = true;
                }
            }
        }
        return result;
    }

    /**
     * 激活互动状态。
     *
     * @param currentUserId 当前用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @return 本次是否真的发生了状态变化
     */
    private boolean activateInteractionState(long currentUserId, String targetType, long targetId, String action) {
        if (socialMapper.reactivateInteraction(currentUserId, targetType, targetId, action) > 0) {
            return true;
        }
        if (socialMapper.existsActiveInteraction(currentUserId, targetType, targetId, action) > 0) {
            return false;
        }
        try {
            socialMapper.insertInteraction(snowflakeIdGenerator.nextId(), currentUserId, targetType, targetId, action);
            return true;
        } catch (DuplicateKeyException ex) {
            if (socialMapper.existsActiveInteraction(currentUserId, targetType, targetId, action) > 0) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 取消互动状态。
     *
     * @param currentUserId 当前用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @return 本次是否真的发生了状态变化
     */
    private boolean deactivateInteractionState(long currentUserId, String targetType, long targetId, String action) {
        return socialMapper.deactivateInteraction(currentUserId, targetType, targetId, action) > 0;
    }

    /**
     * 加载并校验单个目标快照。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 目标快照
     */
    private PostTargetSnapshot loadTargetSnapshot(long currentUserId, String targetType, long targetId) {
        ensureSupportedTargetType(targetType);
        PostTargetSnapshot snapshot = toSnapshot(socialMapper.findPostSnapshot(targetId));
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "目标内容不存在");
        }
        ensureSnapshotAccessible(currentUserId, snapshot);
        if (!snapshot.interactable()) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "当前内容不可互动");
        }
        return snapshot;
    }

    /**
     * 校验当前是否支持指定目标类型。
     *
     * @param targetType 目标类型
     */
    /**
     * 统一校验帖子是否允许当前查看者读取互动信息或发起互动操作。
     *
     * @param currentUserId 当前查看者或操作人 ID，匿名查看时可传 0
     * @param snapshot 目标帖子快照
     */
    private void ensureSnapshotAccessible(long currentUserId, PostTargetSnapshot snapshot) {
        if (!snapshot.interactable()) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "当前内容不可互动");
        }
        if (!canAccessSnapshot(currentUserId, snapshot)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "当前内容暂无访问权限");
        }
    }

    /**
     * 判断当前查看者是否有权访问目标帖子的互动信息。
     *
     * @param currentUserId 当前查看者或操作人 ID
     * @param snapshot 目标帖子快照
     * @return 允许访问返回 true，否则返回 false
     */
    private boolean canAccessSnapshot(long currentUserId, PostTargetSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (currentUserId > 0L && currentUserId == snapshot.getCreatorId()) {
            return true;
        }
        if (snapshot.isPublicVisible()) {
            return true;
        }
        if (snapshot.isFollowersVisible() && currentUserId > 0L) {
            return followService.isFollowing(currentUserId, snapshot.getCreatorId());
        }
        return false;
    }

    private void ensureSupportedTargetType(String targetType) {
        if (!"post".equalsIgnoreCase(targetType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "当前只支持 post 类型");
        }
    }

    /**
     * 读取单个实体计数。
     * 优先读 Redis SDS，缺失时回退数据库聚合。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 长度为 2 的统计数组
     */
    private long[] readEntityCounters(String targetType, long targetId) {
        String key = SocialRedisKeys.entityCounterKey(targetType, targetId);
        byte[] raw = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        long[] baseStats;
        if (isValidEntityCounterRaw(raw)) {
            baseStats = new long[]{
                    readInt32BE(raw, OFFSET_LIKE),
                    readInt32BE(raw, OFFSET_FAVORITE)
            };
        } else {
            long[] rebuilt = tryRebuildEntityCounter(targetType, targetId);
            if (rebuilt != null) {
                baseStats = rebuilt;
            } else {
                Long likeCount = socialMapper.aggregateActiveInteractionCount(targetType, targetId, "like");
                Long favoriteCount = socialMapper.aggregateActiveInteractionCount(targetType, targetId, "favorite");
                baseStats = new long[]{
                        likeCount == null ? 0L : likeCount,
                        favoriteCount == null ? 0L : favoriteCount
                };
                writeEntityCounterSnapshot(targetType, targetId, baseStats[0], baseStats[1]);
                clearAggregateBucket(targetType, targetId);
            }
        }

        long[] delta = readAggregateDelta(targetType, targetId);
        return new long[]{baseStats[0] + delta[0], baseStats[1] + delta[1]};
    }

    /**
     * 读取单个实体的近实时聚合增量。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 聚合增量数组
     */
    private long[] readAggregateDelta(String targetType, long targetId) {
        List<Object> values = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] aggKey = SocialRedisKeys.aggregateBucketKey(targetType, targetId).getBytes(StandardCharsets.UTF_8);
            connection.hashCommands().hGet(aggKey, AGGREGATE_FIELD_LIKE.getBytes(StandardCharsets.UTF_8));
            connection.hashCommands().hGet(aggKey, AGGREGATE_FIELD_FAVORITE.getBytes(StandardCharsets.UTF_8));
            return null;
        });
        return new long[]{
                toLongResult(values != null && !values.isEmpty() ? values.get(0) : null),
                toLongResult(values != null && values.size() > 1 ? values.get(1) : null)
        };
    }

    /**
     * 批量读取多个实体的近实时聚合增量。
     *
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表
     * @return 以目标 ID 为键的聚合增量映射
     */
    private Map<Long, long[]> readAggregateDeltasBatch(String targetType, List<Long> targetIds) {
        Map<Long, long[]> result = new LinkedHashMap<Long, long[]>();
        if (targetIds == null || targetIds.isEmpty()) {
            return result;
        }

        List<Object> values = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long targetId : targetIds) {
                byte[] aggKey = SocialRedisKeys.aggregateBucketKey(targetType, targetId).getBytes(StandardCharsets.UTF_8);
                connection.hashCommands().hGet(aggKey, AGGREGATE_FIELD_LIKE.getBytes(StandardCharsets.UTF_8));
                connection.hashCommands().hGet(aggKey, AGGREGATE_FIELD_FAVORITE.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        int index = 0;
        for (Long targetId : targetIds) {
            Object likeValue = values != null && index < values.size() ? values.get(index) : null;
            Object favoriteValue = values != null && index + 1 < values.size() ? values.get(index + 1) : null;
            result.put(targetId, new long[]{toLongResult(likeValue), toLongResult(favoriteValue)});
            index += 2;
        }
        return result;
    }

    /**
     * 判断指定用户对目标内容的互动状态是否生效。
     *
     * @param userId 用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @return 生效返回 true，否则返回 false
     */
    private boolean isInteractionActive(long userId, String targetType, long targetId, String action) {
        if (isBitmapMarked(action, targetType, targetId, userId)) {
            return true;
        }
        return socialMapper.existsActiveInteraction(userId, targetType, targetId, action) > 0;
    }

    /**
     * 判断位图中是否存在当前用户状态。
     *
     * @param action 动作类型
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param userId 用户 ID
     * @return 命中返回 true，否则返回 false
     */
    private boolean isBitmapMarked(String action, String targetType, long targetId, long userId) {
        String metric = resolveBitmapMetric(action);
        String key = SocialRedisKeys.bitmapKey(metric, targetType, targetId, SocialRedisKeys.chunkOf(userId));
        long bitOffset = SocialRedisKeys.bitOffsetOf(userId);
        Boolean bit = stringRedisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().getBit(key.getBytes(StandardCharsets.UTF_8), bitOffset));
        return Boolean.TRUE.equals(bit);
    }

    /**
     * 同步互动位图。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param userId 用户 ID
     * @param action 动作类型
     * @param active true 表示生效，false 表示取消
     */
    private void syncBitmap(String targetType, long targetId, long userId, String action, boolean active) {
        String metric = resolveBitmapMetric(action);
        String key = SocialRedisKeys.bitmapKey(metric, targetType, targetId, SocialRedisKeys.chunkOf(userId));
        stringRedisTemplate.execute(
                bitmapToggleScript,
                Collections.singletonList(key),
                String.valueOf(SocialRedisKeys.bitOffsetOf(userId)),
                active ? "add" : "remove"
        );
    }

    /**
     * 同步实体计数 SDS。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @param delta 变化量
     */
    private void incrementEntityCounter(String targetType, long targetId, String action, int delta) {
        int fieldIndex = "like".equals(action) ? SocialCounterSchema.IDX_LIKE : SocialCounterSchema.IDX_FAV;
        stringRedisTemplate.execute(
                entityCounterIncrementScript,
                Collections.singletonList(SocialRedisKeys.entityCounterKey(targetType, targetId)),
                String.valueOf(SocialCounterSchema.SCHEMA_LEN),
                String.valueOf(SocialCounterSchema.FIELD_SIZE),
                String.valueOf(fieldIndex),
                String.valueOf(delta)
        );
    }

    /**
     * 将本次互动增量写入聚合桶，供后台折叠进实体计数快照。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @param delta 变化量
     */
    private void incrementAggregateBucket(String targetType, long targetId, String action, int delta) {
        if (delta == 0) {
            return;
        }
        String aggKey = SocialRedisKeys.aggregateBucketKey(targetType, targetId);
        String field = "like".equals(action) ? AGGREGATE_FIELD_LIKE : AGGREGATE_FIELD_FAVORITE;
        stringRedisTemplate.opsForHash().increment(aggKey, field, delta);
    }

    /**
     * 清理实体聚合桶。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     */
    private void clearAggregateBucket(String targetType, long targetId) {
        stringRedisTemplate.delete(SocialRedisKeys.aggregateBucketKey(targetType, targetId));
    }

    /**
     * 定时将聚合桶折叠进实体计数快照。
     * 当前项目先用轻量扫描实现，后续接 Kafka 后再替换为真正的消费聚合链。
     */
    @Scheduled(fixedDelayString = "${social.aggregate-flush-delay-ms:3000}")
    public void flushAggregateBuckets() {
        if (counterEventProducer.isEnabled()) {
            return;
        }
        try {
            flushAggregateBucketsNow();
        } catch (RedisConnectionFailureException ex) {
            log.debug("skip aggregate bucket flush because redis is unavailable", ex);
        }
    }

    /**
     * 立即执行一次聚合桶刷写。
     * 供本地调度链或 Kafka 聚合消费者统一复用。
     */
    @Override
    public void flushAggregateBucketsNow() {
        List<String> keys = scanRedisKeys(SocialRedisKeys.aggregateBucketPattern());
        if (keys.isEmpty()) {
            return;
        }

        for (String aggKey : keys) {
            AggregateBucketTarget target = parseAggregateBucketKey(aggKey);
            if (target == null) {
                continue;
            }
            try {
                foldAggregateBucket(target.targetType, target.targetId);
            } catch (Exception ex) {
                log.warn("flush aggregate bucket failed, aggKey={}", aggKey, ex);
            }
        }
    }

    /**
     * 接收 Kafka 聚合事件并写入本地聚合桶。
     *
     * @param event Kafka 计数事件
     */
    @Override
    public void acceptAggregateEvent(CounterEvent event) {
        if (event == null) {
            return;
        }

        long targetId;
        try {
            targetId = Long.parseLong(event.getEntityId());
        } catch (NumberFormatException ex) {
            log.warn("ignore counter event with invalid entity id, entityType={}, entityId={}",
                    event.getEntityType(), event.getEntityId(), ex);
            return;
        }

        String action = resolveActionByCounterEvent(event);
        if (action == null) {
            log.warn("ignore counter event with unsupported metric, entityType={}, entityId={}, metric={}, idx={}",
                    event.getEntityType(), event.getEntityId(), event.getMetric(), event.getIdx());
            return;
        }

        incrementAggregateBucket(event.getEntityType(), targetId, action, event.getDelta());
    }

    /**
     * 原子地将单个聚合桶折叠进实体计数快照，并清空聚合桶。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     */
    private void foldAggregateBucket(String targetType, long targetId) {
        String result = stringRedisTemplate.execute(
                aggregateFoldScript,
                java.util.Arrays.asList(
                        SocialRedisKeys.aggregateBucketKey(targetType, targetId),
                        SocialRedisKeys.entityCounterKey(targetType, targetId)
                ),
                String.valueOf(SocialCounterSchema.SCHEMA_LEN),
                String.valueOf(SocialCounterSchema.FIELD_SIZE),
                AGGREGATE_FIELD_LIKE,
                AGGREGATE_FIELD_FAVORITE
        );
        if (result != null && !"0,0".equals(result)) {
            log.debug("fold aggregate bucket success, targetType={}, targetId={}, delta={}", targetType, targetId, result);
        }
    }

    /**
     * 解析聚合桶 key 中的目标信息。
     *
     * @param aggKey 聚合桶 key
     * @return 解析成功返回目标信息，否则返回 null
     */
    private AggregateBucketTarget parseAggregateBucketKey(String aggKey) {
        if (aggKey == null || aggKey.trim().isEmpty()) {
            return null;
        }
        String[] parts = aggKey.split(":", 4);
        if (parts.length != 4) {
            return null;
        }
        try {
            return new AggregateBucketTarget(parts[2], Long.parseLong(parts[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 解析动作对应的事件类型。
     *
     * @param action 动作类型
     * @return 事件类型
     */
    /**
     * 按需从位图事实层重建帖子维计数。
     * 这里使用轻量重建锁，避免同一帖子在短时间内被重复重建。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 重建成功返回计数数组；当前不适合重建时返回 null
     */
    private long[] tryRebuildEntityCounter(String targetType, long targetId) {
        if (inRebuildBackoff(targetType, targetId)) {
            return null;
        }

        if (!allowedByRebuildRateLimiter(targetType, targetId)) {
            escalateRebuildBackoff(targetType, targetId);
            return null;
        }

        String lockKey = SocialRedisKeys.entityCounterRebuildLockKey(targetType, targetId);
        String lockValue = String.valueOf(System.nanoTime());
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                Duration.ofMillis(rebuildLockTtlMs)
        );
        if (!Boolean.TRUE.equals(locked)) {
            escalateRebuildBackoff(targetType, targetId);
            return null;
        }

        try {
            Long likeCount = bitCountShardsPipelined("like", targetType, targetId);
            Long favoriteCount = bitCountShardsPipelined("fav", targetType, targetId);
            if (likeCount == null && favoriteCount == null) {
                return null;
            }

            if (likeCount == null) {
                likeCount = socialMapper.aggregateActiveInteractionCount(targetType, targetId, "like");
            }
            if (favoriteCount == null) {
                favoriteCount = socialMapper.aggregateActiveInteractionCount(targetType, targetId, "favorite");
            }

            long safeLikeCount = likeCount == null ? 0L : likeCount;
            long safeFavoriteCount = favoriteCount == null ? 0L : favoriteCount;
            writeEntityCounterSnapshot(targetType, targetId, safeLikeCount, safeFavoriteCount);
            stringRedisTemplate.delete(SocialRedisKeys.aggregateBucketKey(targetType, targetId));
            resetRebuildBackoff(targetType, targetId);
            return new long[]{safeLikeCount, safeFavoriteCount};
        } catch (Exception ex) {
            escalateRebuildBackoff(targetType, targetId);
            log.warn("rebuild entity counter from bitmap failed, targetType={}, targetId={}", targetType, targetId, ex);
            return null;
        } finally {
            releaseRebuildLock(lockKey, lockValue);
        }
    }

    /**
     * 判断当前实体是否仍处于重建退避期。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 仍在退避期返回 true，否则返回 false
     */
    private boolean inRebuildBackoff(String targetType, long targetId) {
        String until = stringRedisTemplate.opsForValue()
                .get(SocialRedisKeys.entityCounterRebuildBackoffUntilKey(targetType, targetId));
        if (until == null || until.trim().isEmpty()) {
            return false;
        }
        try {
            return System.currentTimeMillis() < Long.parseLong(until);
        } catch (NumberFormatException ex) {
            stringRedisTemplate.delete(SocialRedisKeys.entityCounterRebuildBackoffUntilKey(targetType, targetId));
            return false;
        }
    }

    /**
     * 判断当前实体是否允许再次触发重建。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 允许返回 true，否则返回 false
     */
    private boolean allowedByRebuildRateLimiter(String targetType, long targetId) {
        Long allowed = stringRedisTemplate.execute(
                rebuildRateLimitScript,
                Collections.singletonList(SocialRedisKeys.entityCounterRebuildRateLimitKey(targetType, targetId)),
                String.valueOf(rebuildRatePermits),
                String.valueOf(rebuildRateWindowSeconds * 1000L)
        );
        return allowed != null && allowed.longValue() == 1L;
    }

    /**
     * 提升当前实体的重建退避等级。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     */
    private void escalateRebuildBackoff(String targetType, long targetId) {
        String expKey = SocialRedisKeys.entityCounterRebuildBackoffExpKey(targetType, targetId);
        Long nextExp = stringRedisTemplate.opsForValue().increment(expKey);
        if (nextExp != null && nextExp.longValue() == 1L) {
            stringRedisTemplate.expire(expKey, Duration.ofHours(1));
        }

        long exp = nextExp == null ? 0L : Math.max(0L, nextExp.longValue() - 1L);
        exp = Math.min(exp, 10L);
        long delay = Math.min(rebuildBackoffBaseMs * (1L << exp), rebuildBackoffMaxMs);
        long until = System.currentTimeMillis() + delay;
        stringRedisTemplate.opsForValue().set(
                SocialRedisKeys.entityCounterRebuildBackoffUntilKey(targetType, targetId),
                String.valueOf(until),
                Duration.ofMillis(delay + 1000L)
        );
    }

    /**
     * 重置当前实体的重建退避状态。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     */
    private void resetRebuildBackoff(String targetType, long targetId) {
        stringRedisTemplate.delete(SocialRedisKeys.entityCounterRebuildBackoffExpKey(targetType, targetId));
        stringRedisTemplate.delete(SocialRedisKeys.entityCounterRebuildBackoffUntilKey(targetType, targetId));
    }

    /**
     * 按 token 安全释放重建锁，避免误删其他请求的新锁。
     *
     * @param lockKey 锁键
     * @param lockValue 锁值
     */
    private void releaseRebuildLock(String lockKey, String lockValue) {
        stringRedisTemplate.execute(
                rebuildLockReleaseScript,
                Collections.singletonList(lockKey),
                lockValue
        );
    }

    /**
     * 对指定实体的所有位图分片做管道化 BITCOUNT 汇总。
     * 当前阶段先采用 KEYS + BITCOUNT 的收缩实现，后续再演进为更稳定的分片索引方案。
     *
     * @param metric 指标名称
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 若存在位图分片则返回汇总值；若完全不存在分片则返回 null
     */
    private Long bitCountShardsPipelined(String metric, String targetType, long targetId) {
        List<String> keys = scanRedisKeys(SocialRedisKeys.bitmapPattern(metric, targetType, targetId));
        if (keys.isEmpty()) {
            return null;
        }

        List<Object> pipelineResult = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.stringCommands().bitCount(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        long sum = 0L;
        if (pipelineResult != null) {
            for (Object value : pipelineResult) {
                if (value instanceof Number) {
                    sum += ((Number) value).longValue();
                }
            }
        }
        return sum;
    }

    private List<String> scanRedisKeys(String pattern) {
        try {
            List<String> keys = stringRedisTemplate.execute((RedisCallback<List<String>>) connection -> {
                List<String> result = new ArrayList<String>();
                ScanOptions options = ScanOptions.scanOptions()
                        .match(pattern)
                        .count(500)
                        .build();
                Cursor<byte[]> cursor = connection.scan(options);
                try {
                    while (cursor.hasNext()) {
                        result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                } finally {
                    try {
                        cursor.close();
                    } catch (Exception ignored) {
                        // Ignore scan cursor close failures; the scan itself is best-effort.
                    }
                }
                return result;
            });
            return keys == null ? Collections.emptyList() : keys;
        } catch (Exception ex) {
            log.warn("scan redis keys failed, pattern={}", pattern, ex);
            return Collections.emptyList();
        }
    }

    /**
     * 将实体计数整体写回 Redis SDS。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param likeCount 点赞数
     * @param favoriteCount 收藏数
     */
    private void writeEntityCounterSnapshot(String targetType, long targetId, long likeCount, long favoriteCount) {
        byte[] raw = new byte[SocialCounterSchema.SCHEMA_LEN * SocialCounterSchema.FIELD_SIZE];
        writeInt32BE(raw, OFFSET_LIKE, likeCount);
        writeInt32BE(raw, OFFSET_FAVORITE, favoriteCount);
        byte[] key = SocialRedisKeys.entityCounterKey(targetType, targetId).getBytes(StandardCharsets.UTF_8);
        stringRedisTemplate.execute((RedisCallback<Boolean>) connection -> {
            connection.stringCommands().set(key, raw);
            return Boolean.TRUE;
        });
    }

    /**
     * 判断实体计数 SDS 结构是否完整。
     *
     * @param raw Redis 原始字节数组
     * @return 结构完整返回 true，否则返回 false
     */
    private boolean isValidEntityCounterRaw(byte[] raw) {
        return raw != null && raw.length == SocialCounterSchema.SCHEMA_LEN * SocialCounterSchema.FIELD_SIZE;
    }

    private String resolveEventType(String action) {
        return "like".equals(action) ? "LIKE_CHANGED" : "FAVORITE_CHANGED";
    }

    /**
     * 解析动作对应的位图指标名。
     *
     * @param action 动作类型
     * @return 位图指标名
     */
    private String resolveBitmapMetric(String action) {
        return "like".equals(action) ? "like" : "fav";
    }

    private int resolveCounterFieldIndex(String action) {
        return "like".equals(action) ? SocialCounterSchema.IDX_LIKE : SocialCounterSchema.IDX_FAV;
    }

    /**
     * 根据 Kafka 计数事件还原内部动作名称。
     *
     * @param event Kafka 计数事件
     * @return 返回 like 或 favorite；不支持时返回 null
     */
    private String resolveActionByCounterEvent(CounterEvent event) {
        if (event == null) {
            return null;
        }
        if ("like".equals(event.getMetric()) || event.getIdx() == SocialCounterSchema.IDX_LIKE) {
            return "like";
        }
        if ("fav".equals(event.getMetric()) || event.getIdx() == SocialCounterSchema.IDX_FAV) {
            return "favorite";
        }
        return null;
    }

    /**
     * 将位图读取结果转成布尔值。
     *
     * @param value Redis 读取结果
     * @return 布尔状态
     */
    private boolean toBooleanResult(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue() > 0L;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 将 Redis 读取结果转换为 long。
     *
     * @param value Redis 读取结果
     * @return 数值结果
     */
    private long toLongResult(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof byte[]) {
            try {
                return Long.parseLong(new String((byte[]) value, StandardCharsets.UTF_8));
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 按大端序读取 4 字节无符号整数。
     *
     * @param buffer 原始字节数组
     * @param offset 偏移量
     * @return 解析后的数值
     */
    private long readInt32BE(byte[] buffer, int offset) {
        long value = 0L;
        for (int i = 0; i < SocialCounterSchema.FIELD_SIZE; i++) {
            value = (value << 8) | (buffer[offset + i] & 0xFFL);
        }
        return value;
    }

    /**
     * 按大端序写入 4 字节无符号整数。
     *
     * @param buffer 原始字节数组
     * @param offset 偏移量
     * @param value 待写入的计数值
     */
    private void writeInt32BE(byte[] buffer, int offset, long value) {
        long safeValue = Math.max(0L, Math.min(value, 0xFFFF_FFFFL));
        buffer[offset] = (byte) ((safeValue >>> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((safeValue >>> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((safeValue >>> 8) & 0xFF);
        buffer[offset + 3] = (byte) (safeValue & 0xFF);
    }

    /**
     * 聚合桶目标信息。
     */
    private static final class AggregateBucketTarget {
        private final String targetType;
        private final long targetId;

        private AggregateBucketTarget(String targetType, long targetId) {
            this.targetType = targetType;
            this.targetId = targetId;
        }
    }
}
