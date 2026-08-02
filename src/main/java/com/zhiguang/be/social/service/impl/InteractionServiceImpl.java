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
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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
    static final String AGGREGATE_EVENT_CONSUME_SCRIPT =
            "local dedupKey = KEYS[1]\n"
                    + "local aggKey = KEYS[2]\n"
                    + "local seenValue = ARGV[1]\n"
                    + "local ttlSeconds = tonumber(ARGV[2])\n"
                    + "local field = ARGV[3]\n"
                    + "local delta = tonumber(ARGV[4])\n"
                    + "local marked = redis.call('SET', dedupKey, seenValue, 'NX', 'EX', ttlSeconds)\n"
                    + "if not marked then return 0 end\n"
                    + "local result = redis.pcall('HINCRBY', aggKey, field, delta)\n"
                    + "if type(result) == 'table' and result['err'] then\n"
                    + "  redis.call('DEL', dedupKey)\n"
                    + "  return -1\n"
                    + "end\n"
                    + "return 1\n";

    private final SocialMapper socialMapper;
    private final FollowService followService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final UserSocialCounterService userSocialCounterService;
    private final CounterEventProducer counterEventProducer;
    private final LbsDiscoverService lbsDiscoverService;
    private final FeedCacheInvalidationService feedCacheInvalidationService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor interactionProjectionExecutor;
    private final DefaultRedisScript<Long> bitmapToggleScript;
    private final DefaultRedisScript<Long> entityCounterIncrementScript;
    private final DefaultRedisScript<String> aggregateFoldScript;
    private final DefaultRedisScript<Long> aggregateEventConsumeScript;
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
    @Value("${social.counter.event-dedup-ttl-days:7}")
    private long counterEventDedupTtlDays = 7L;
    @Value("${social.interaction-projection.async-enabled:false}")
    private boolean asyncProjectionEnabled;

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
            ObjectMapper objectMapper,
            @Qualifier("interactionProjectionExecutor") Executor interactionProjectionExecutor
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
        this.interactionProjectionExecutor = interactionProjectionExecutor;

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
                        + "  for i = 1, 4 do n = n * 256 + (b[i] or 0) end\n"
                        + "  return n\n"
                        + "end\n"
                        + "local function write32be(n)\n"
                        + "  local t = {}\n"
                        + "  for i = 4, 1, -1 do t[i] = n % 256; n = math.floor(n / 256) end\n"
                        + "  return string.char(unpack(t))\n"
                        + "end\n"
                        + "local cnt = redis.call('GET', cntKey)\n"
                        + "if not cnt or string.len(cnt) < schemaLen * fieldSize then cnt = string.rep(string.char(0), schemaLen * fieldSize) end\n"
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
                        + "local likeOffset = tonumber(ARGV[5])\n"
                        + "local favoriteOffset = tonumber(ARGV[6])\n"
                        + "local function read32be(s, off)\n"
                        + "  local b = {string.byte(s, off + 1, off + 4)}\n"
                        + "  local n = 0\n"
                        + "  for i = 1, 4 do n = n * 256 + (b[i] or 0) end\n"
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
                        + "if not cnt or string.len(cnt) < schemaLen * fieldSize then cnt = string.rep(string.char(0), schemaLen * fieldSize) end\n"
                        + "local likeValue = read32be(cnt, likeOffset) + likeDelta\n"
                        + "if likeValue < 0 then likeValue = 0 end\n"
                        + "cnt = writeValue(cnt, likeOffset, likeValue)\n"
                        + "local favoriteValue = read32be(cnt, favoriteOffset) + favoriteDelta\n"
                        + "if favoriteValue < 0 then favoriteValue = 0 end\n"
                        + "cnt = writeValue(cnt, favoriteOffset, favoriteValue)\n"
                        + "redis.call('SET', cntKey, cnt)\n"
                        + "redis.call('DEL', aggKey)\n"
                        + "return tostring(likeDelta) .. ',' .. tostring(favoriteDelta)\n"
        );

        this.aggregateEventConsumeScript = new DefaultRedisScript<Long>();
        this.aggregateEventConsumeScript.setResultType(Long.class);
        this.aggregateEventConsumeScript.setScriptText(AGGREGATE_EVENT_CONSUME_SCRIPT);

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

        Transactions.runAfterCommit(() -> dispatchInteractionProjection(() -> applyInteractionProjection(
                snapshot, targetType, targetId, currentUserId, action, active, delta, String.valueOf(eventId)
        )));

        return buildActionData(targetType, targetId, action, active, true);
    }

    private void dispatchInteractionProjection(Runnable projection) {
        if (!asyncProjectionEnabled) {
            projection.run();
            return;
        }
        try {
            interactionProjectionExecutor.execute(projection);
        } catch (RejectedExecutionException ex) {
            // Keep correctness ahead of latency when the bounded projection queue is saturated.
            log.warn("interaction projection executor rejected task; running on caller thread", ex);
            projection.run();
        }
    }

    /**
     * 异步投影互动事件的 Redis 侧状态。
     * 位图与计数任一链路失败时只登记重试标记，不回滚主库互动事实，保证用户操作先落库再最终一致补偿。
     *
     * @param snapshot 目标内容快照
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param currentUserId 当前用户 ID
     * @param action 动作类型
     * @param active true 表示生效，false 表示取消
     * @param delta 计数增量
     * @param eventId 业务事件 ID
     */
    private void applyInteractionProjection(
            PostTargetSnapshot snapshot,
            String targetType,
            long targetId,
            long currentUserId,
            String action,
            boolean active,
            int delta,
            String eventId
    ) {
        boolean needsRedisRetry = false;
        try {
            syncBitmap(targetType, targetId, currentUserId, action, active);
        } catch (Exception ex) {
            needsRedisRetry = true;
            log.warn("sync interaction bitmap failed, userId={}, targetType={}, targetId={}, action={}, active={}",
                    currentUserId, targetType, targetId, action, active, ex);
        }

        try {
            projectEntityCounterDelta(targetType, targetId, currentUserId, action, delta, eventId);
        } catch (Exception ex) {
            needsRedisRetry = true;
            log.warn("project interaction counter failed, userId={}, targetType={}, targetId={}, action={}, delta={}",
                    currentUserId, targetType, targetId, action, delta, ex);
        }

        if (needsRedisRetry) {
            scheduleBitmapRetry(targetType, targetId, currentUserId, action, active);
        }

        try {
            invalidateFeedCache(targetType, targetId);
        } catch (Exception ex) {
            log.warn("invalidate interaction feed cache failed, targetType={}, targetId={}", targetType, targetId, ex);
        }

        try {
            if ("like".equals(action)) {
                userSocialCounterService.incrementLikesReceived(snapshot.getCreatorId(), delta);
            } else {
                userSocialCounterService.incrementFavoritesReceived(snapshot.getCreatorId(), delta);
            }
        } catch (Exception ex) {
            log.warn("update author interaction counters failed, creatorId={}, action={}, delta={}",
                    snapshot.getCreatorId(), action, delta, ex);
        }

        try {
            refreshDiscoverInteractionStats(snapshot, targetType, targetId, action, delta);
        } catch (Exception ex) {
            log.warn("refresh discover interaction stats failed, targetType={}, targetId={}, action={}, delta={}",
                    targetType, targetId, action, delta, ex);
        }
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
        feedCacheInvalidationService.invalidatePostFragmentAfterCommit(String.valueOf(targetId));
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
                // Atomically read aggregate deltas and clear the bucket
                long[] aggregateDeltas = foldAndClearAggregateBucket(targetType, targetId);
                stats[0] += aggregateDeltas[0];
                stats[1] += aggregateDeltas[1];
                writeEntityCounterSnapshot(targetType, targetId, stats[0], stats[1]);
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
                // Atomically fold aggregate deltas into snapshot before writing
                long[] aggregateDeltas = foldAndClearAggregateBucket(targetType, targetId);
                baseStats[0] += aggregateDeltas[0];
                baseStats[1] += aggregateDeltas[1];
                writeEntityCounterSnapshot(targetType, targetId, baseStats[0], baseStats[1]);
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
     * Lua 必须明确返回 0 或 1，null/负数视为写入链路异常，交给外层投影流程登记重试标记。
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
        Long result = stringRedisTemplate.execute(
                bitmapToggleScript,
                Collections.singletonList(key),
                String.valueOf(SocialRedisKeys.bitOffsetOf(userId)),
                active ? "add" : "remove"
        );
        if (result == null) {
            throw new IllegalStateException("interaction bitmap toggle script returned null");
        }
        if (result.longValue() < 0L) {
            throw new IllegalStateException("interaction bitmap toggle script returned invalid operation");
        }
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
     * 投影实体计数增量。
     * Kafka 开启时优先发布事件；发布失败属于“不确定是否已写入 Kafka”的场景，
     * 本地 fallback 也必须走同一 eventId 原子去重脚本，避免 Kafka 实际写入成功后 consumer 重复计数。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param userId 操作用户 ID
     * @param action 动作类型
     * @param delta 计数增量
     * @param eventId 业务事件 ID
     */
    private void projectEntityCounterDelta(String targetType, long targetId, long userId, String action, int delta, String eventId) {
        CounterEvent counterEvent = CounterEvent.of(
                targetType,
                String.valueOf(targetId),
                resolveBitmapMetric(action),
                resolveCounterFieldIndex(action),
                userId,
                delta,
                eventId
        );
        if (counterEventProducer.isEnabled()) {
            if (counterEventProducer.publish(counterEvent)) {
                return;
            }
            consumeAggregateEventAtomically(counterEvent, targetId, action);
            return;
        }
        incrementAggregateBucket(targetType, targetId, action, delta);
    }

    /**
     * 写入 Redis 重试标记，供定时任务兜底补偿失败的位图/聚合桶同步。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param currentUserId 当前用户 ID
     * @param action 动作类型
     * @param active 是否生效
     */
    private void scheduleBitmapRetry(String targetType, long targetId, long currentUserId, String action, boolean active) {
        String retryKey = SocialRedisKeys.interactionRetryKey(targetType, targetId, currentUserId, action);
        String retryValue = active ? "1" : "0";
        try {
            stringRedisTemplate.opsForValue().set(retryKey, retryValue, Duration.ofMinutes(5));
        } catch (Exception ignore) {
            // Silently ignore if Redis is also unavailable
        }
    }

    /**
     * 定时扫描重试标记并重放失败的位图同步。
     */
    @Scheduled(fixedDelayString = "${social.interaction-retry-delay-ms:10000}")
    public void retryFailedBitmapSyncs() {
        List<String> keys = scanRedisKeys("retry:interaction:*");
        for (String key : keys) {
            try {
                String[] parts = key.split(":");
                if (parts.length >= 6) {
                    String type = parts[2];
                    long targetId = Long.parseLong(parts[3]);
                    long userId = Long.parseLong(parts[4]);
                    String action = parts[5];
                    boolean active = "1".equals(stringRedisTemplate.opsForValue().get(key));
                    syncBitmap(type, targetId, userId, action, active);
                    rebuildEntityCounterFromDb(type, targetId);
                    stringRedisTemplate.delete(key);
                }
            } catch (Exception ex) {
                log.debug("Retry interaction bitmap sync failed for key={}", key, ex);
            }
        }
    }

    /**
     * 原子地读取聚合桶增量并清空桶。
     * 使用单个 Lua 脚本避免在读取和删除之间存在竞态窗口。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 长度为 2 的数组，下标 0 为点赞增量，下标 1 为收藏增量
     */
    private long[] foldAndClearAggregateBucket(String targetType, long targetId) {
        String aggKey = SocialRedisKeys.aggregateBucketKey(targetType, targetId);
        String luaScript =
                "local likeVal = redis.call('HGET', KEYS[1], ARGV[1]) or '0'\n" +
                        "local favVal = redis.call('HGET', KEYS[1], ARGV[2]) or '0'\n" +
                        "redis.call('DEL', KEYS[1])\n" +
                        "return {likeVal, favVal}";
        DefaultRedisScript<List> script = new DefaultRedisScript<>(luaScript, List.class);
        List<?> result = stringRedisTemplate.execute(
                script,
                List.of(aggKey),
                AGGREGATE_FIELD_LIKE,
                AGGREGATE_FIELD_FAVORITE
        );
        if (result == null || result.size() < 2) {
            return new long[]{0L, 0L};
        }
        return new long[]{Long.parseLong(String.valueOf(result.get(0))), Long.parseLong(String.valueOf(result.get(1)))};
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

        if (!consumeAggregateEventAtomically(event, targetId, action)) {
            return;
        }
    }

    /**
     * 原子消费计数事件。
     * 新事件在同一个 Redis Lua 脚本中完成 eventId 去重和聚合桶增量写入，避免两步操作导致重复或丢计数；
     * 旧消息没有 eventId 时保持兼容，继续走原来的聚合桶写入。
     *
     * @param event Kafka 计数事件
     * @param targetId 目标 ID
     * @param action 动作类型
     * @return 首次消费或旧消息返回 true，重复事件返回 false
     */
    private boolean consumeAggregateEventAtomically(CounterEvent event, long targetId, String action) {
        String eventId = event.getEventId();
        if (eventId == null || eventId.trim().isEmpty()) {
            log.debug("consume legacy counter event without eventId, entityType={}, entityId={}, metric={}",
                    event.getEntityType(), event.getEntityId(), event.getMetric());
            incrementAggregateBucket(event.getEntityType(), targetId, action, event.getDelta());
            return true;
        }

        String field = "like".equals(action) ? AGGREGATE_FIELD_LIKE : AGGREGATE_FIELD_FAVORITE;
        Long result = stringRedisTemplate.execute(
                aggregateEventConsumeScript,
                java.util.Arrays.asList(
                        SocialRedisKeys.counterEventDedupKey(eventId),
                        SocialRedisKeys.aggregateBucketKey(event.getEntityType(), targetId)
                ),
                "1",
                String.valueOf(Duration.ofDays(Math.max(1L, counterEventDedupTtlDays)).getSeconds()),
                field,
                String.valueOf(event.getDelta())
        );
        if (result == null) {
            throw new IllegalStateException("counter event atomic consume script returned null");
        }
        if (result.longValue() < 0L) {
            throw new IllegalStateException("counter event aggregate write failed inside atomic script");
        }
        if (result.longValue() == 0L) {
            log.debug("ignore duplicate counter event, eventId={}, entityType={}, entityId={}",
                    eventId, event.getEntityType(), event.getEntityId());
            return false;
        }
        return true;
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
                AGGREGATE_FIELD_FAVORITE,
                String.valueOf(OFFSET_LIKE),
                String.valueOf(OFFSET_FAVORITE)
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
            long[] rebuilt = rebuildEntityCounterFromDb(targetType, targetId);
            resetRebuildBackoff(targetType, targetId);
            return rebuilt;
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

    private long[] rebuildEntityCounterFromDb(String targetType, long targetId) {
        Long likeCount = socialMapper.aggregateActiveInteractionCount(targetType, targetId, "like");
        Long favoriteCount = socialMapper.aggregateActiveInteractionCount(targetType, targetId, "favorite");
        long safeLikeCount = likeCount == null ? 0L : likeCount;
        long safeFavoriteCount = favoriteCount == null ? 0L : favoriteCount;
        writeEntityCounterSnapshot(targetType, targetId, safeLikeCount, safeFavoriteCount);
        stringRedisTemplate.delete(SocialRedisKeys.aggregateBucketKey(targetType, targetId));
        return new long[]{safeLikeCount, safeFavoriteCount};
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

    /**
     * 将互动动作映射到 SDS 实体计数字段下标，保证 Kafka 事件与本地紧凑计数布局一致。
     *
     * @param action 动作类型
     * @return SDS 字段下标
     */
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
        return SocialCounterSchema.readInt32BE(buffer, offset);
    }

    /**
     * 按大端序写入 4 字节无符号整数。
     *
     * @param buffer 原始字节数组
     * @param offset 偏移量
     * @param value 待写入的计数值
     */
    private void writeInt32BE(byte[] buffer, int offset, long value) {
        SocialCounterSchema.writeInt32BE(buffer, offset, value);
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
