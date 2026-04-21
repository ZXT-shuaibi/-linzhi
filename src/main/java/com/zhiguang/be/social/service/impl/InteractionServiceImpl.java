package com.zhiguang.be.social.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.social.CounterEventPayload;
import com.zhiguang.be.social.InteractionActionData;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.PostTargetSnapshot;
import com.zhiguang.be.social.SocialRedisKeys;
import com.zhiguang.be.social.mapper.SocialMapper;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.InteractionService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
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
public class InteractionServiceImpl implements InteractionService {

    private static final Logger log = LoggerFactory.getLogger(InteractionServiceImpl.class);

    private static final int ENTITY_FIELD_COUNT = 2;
    private static final int ENTITY_FIELD_SIZE = 4;
    private static final int OFFSET_LIKE = 0;
    private static final int OFFSET_FAVORITE = 4;

    private final SocialMapper socialMapper;
    private final FollowService followService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final UserSocialCounterService userSocialCounterService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> bitmapToggleScript;
    private final DefaultRedisScript<Long> entityCounterIncrementScript;

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
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper
    ) {
        this.socialMapper = socialMapper;
        this.followService = followService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.userSocialCounterService = userSocialCounterService;
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
                        + "local off = (idx - 1) * fieldSize\n"
                        + "local v = read32be(cnt, off) + delta\n"
                        + "if v < 0 then v = 0 end\n"
                        + "local seg = write32be(v)\n"
                        + "cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off + fieldSize + 1)\n"
                        + "redis.call('SET', cntKey, cnt)\n"
                        + "return 1\n"
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
        ensureAuthenticatedUser(currentUserId);
        PostTargetSnapshot snapshot = loadTargetSnapshot(currentUserId, targetType, targetId);
        boolean changed = active
                ? activateInteractionState(currentUserId, targetType, targetId, action)
                : deactivateInteractionState(currentUserId, targetType, targetId, action);

        if (!changed) {
            return buildActionData(targetType, targetId, action, active);
        }

        int delta = active ? 1 : -1;
        String eventType = resolveEventType(action);
        long eventId = snowflakeIdGenerator.nextId();
        socialMapper.insertOutboxEvent(
                eventId,
                "interaction",
                targetId,
                eventType,
                serialize(CounterEventPayload.of(eventId, eventType, targetType, targetId, action, currentUserId, delta))
        );

        runAfterCommit(() -> {
            try {
                syncBitmap(targetType, targetId, currentUserId, action, active);
                incrementEntityCounter(targetType, targetId, action, delta);
                if ("like".equals(action)) {
                    userSocialCounterService.incrementLikesReceived(snapshot.getCreatorId(), delta);
                } else {
                    userSocialCounterService.incrementFavoritesReceived(snapshot.getCreatorId(), delta);
                }
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

        return buildActionData(targetType, targetId, action, active);
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
    private InteractionActionData buildActionData(String targetType, long targetId, String action, boolean active) {
        return new InteractionActionData(
                targetType,
                String.valueOf(targetId),
                action,
                active,
                Math.toIntExact(Instant.now().getEpochSecond())
        );
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
                toLong(postId),
                toLong(creatorId),
                toStringValue(row.get("status")),
                toStringValue(row.get("visible"))
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

        List<Long> fallbackTargetIds = new ArrayList<Long>();
        for (int i = 0; i < targetIds.size(); i++) {
            Long targetId = targetIds.get(i);
            Object value = pipelineResult != null && i < pipelineResult.size() ? pipelineResult.get(i) : null;
            byte[] raw = value instanceof byte[] ? (byte[]) value : null;
            if (raw != null && raw.length == ENTITY_FIELD_COUNT * ENTITY_FIELD_SIZE) {
                result.put(targetId, new long[]{
                        readInt32BE(raw, OFFSET_LIKE),
                        readInt32BE(raw, OFFSET_FAVORITE)
                });
            } else {
                result.put(targetId, new long[]{0L, 0L});
                fallbackTargetIds.add(targetId);
            }
        }

        if (!fallbackTargetIds.isEmpty()) {
            List<Map<String, Object>> rows = socialMapper.aggregateActiveInteractionCountsBatch(targetType, fallbackTargetIds);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    long targetId = toLong(row.get("targetId"));
                    String actionType = toStringValue(row.get("actionType"));
                    long total = toLong(row.get("total"));
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
                long targetId = toLong(row.get("targetId"));
                String actionType = toStringValue(row.get("actionType"));
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
     * 校验当前操作用户是否已登录。
     *
     * @param currentUserId 当前操作用户 ID
     */
    private void ensureAuthenticatedUser(long currentUserId) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "无效的登录态");
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
        if (raw != null && raw.length == ENTITY_FIELD_COUNT * ENTITY_FIELD_SIZE) {
            return new long[]{
                    readInt32BE(raw, OFFSET_LIKE),
                    readInt32BE(raw, OFFSET_FAVORITE)
            };
        }

        Long likeCount = socialMapper.aggregateActiveInteractionCount(targetType, targetId, "like");
        Long favoriteCount = socialMapper.aggregateActiveInteractionCount(targetType, targetId, "favorite");
        return new long[]{
                likeCount == null ? 0L : likeCount,
                favoriteCount == null ? 0L : favoriteCount
        };
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
        int fieldIndex = "like".equals(action) ? 1 : 2;
        stringRedisTemplate.execute(
                entityCounterIncrementScript,
                Collections.singletonList(SocialRedisKeys.entityCounterKey(targetType, targetId)),
                String.valueOf(ENTITY_FIELD_COUNT),
                String.valueOf(ENTITY_FIELD_SIZE),
                String.valueOf(fieldIndex),
                String.valueOf(delta)
        );
    }

    /**
     * 解析动作对应的事件类型。
     *
     * @param action 动作类型
     * @return 事件类型
     */
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
     * 序列化 outbox 事件载荷。
     *
     * @param payload 事件对象
     * @return JSON 字符串
     */
    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "事件序列化失败");
        }
    }

    /**
     * 在事务提交后执行额外逻辑。
     *
     * @param runnable 提交后执行的任务
     */
    private void runAfterCommit(Runnable runnable) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runnable.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }

    /**
     * 将任意对象转成 long。
     *
     * @param value 原始对象
     * @return long 数值
     */
    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * 将任意对象转成字符串。
     *
     * @param value 原始对象
     * @return 字符串值
     */
    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
     * 按大端序读取 4 字节无符号整数。
     *
     * @param buffer 原始字节数组
     * @param offset 偏移量
     * @return 解析后的数值
     */
    private long readInt32BE(byte[] buffer, int offset) {
        long value = 0L;
        for (int i = 0; i < ENTITY_FIELD_SIZE; i++) {
            value = (value << 8) | (buffer[offset + i] & 0xFFL);
        }
        return value;
    }
}
