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
import com.zhiguang.be.social.service.InteractionService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点赞与收藏服务实现。
 * 负责处理互动状态切换、互动汇总查询以及 Redis 位图和计数 SDS 的维护。
 */
@Service
public class InteractionServiceImpl implements InteractionService {

    private static final Logger log = LoggerFactory.getLogger(InteractionServiceImpl.class);

    private static final int ENTITY_FIELD_COUNT = 2;
    private static final int ENTITY_FIELD_SIZE = 4;
    private static final int OFFSET_LIKE = 0;
    private static final int OFFSET_FAVORITE = 4;

    private final SocialMapper socialMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final UserSocialCounterService userSocialCounterService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> bitmapToggleScript;
    private final DefaultRedisScript<Long> entityCounterIncrementScript;

    /**
     * 构造点赞与收藏服务实现。
     *
     * @param socialMapper 社交模块统一数据访问接口
     * @param snowflakeIdGenerator 雪花 ID 生成器
     * @param userSocialCounterService 用户维社交计数服务
     * @param stringRedisTemplate Redis 字符串模板
     * @param objectMapper JSON 序列化组件
     */
    public InteractionServiceImpl(
            SocialMapper socialMapper,
            SnowflakeIdGenerator snowflakeIdGenerator,
            UserSocialCounterService userSocialCounterService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper
    ) {
        this.socialMapper = socialMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.userSocialCounterService = userSocialCounterService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;

        this.bitmapToggleScript = new DefaultRedisScript<>();
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

        this.entityCounterIncrementScript = new DefaultRedisScript<>();
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
     * 对目标内容执行点赞操作。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 点赞动作结果
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
     * @return 取消点赞动作结果
     */
    @Override
    @Transactional
    public InteractionActionData unlike(long currentUserId, String targetType, long targetId) {
        return changeInteraction(currentUserId, targetType, targetId, "like", false);
    }

    /**
     * 对目标内容执行收藏操作。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 收藏动作结果
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
     * @return 取消收藏动作结果
     */
    @Override
    @Transactional
    public InteractionActionData unfavorite(long currentUserId, String targetType, long targetId) {
        return changeInteraction(currentUserId, targetType, targetId, "favorite", false);
    }

    /**
     * 查询单个目标内容的互动汇总。
     *
     * @param currentUserId 当前查看用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 互动汇总
     */
    @Override
    public InteractionSummary summary(long currentUserId, String targetType, long targetId) {
        loadTargetSnapshot(targetType, targetId);
        long[] stats = readEntityCounters(targetType, targetId);
        boolean viewerLiked = currentUserId > 0 && isInteractionActive(currentUserId, targetType, targetId, "like");
        boolean viewerFavorited = currentUserId > 0 && isInteractionActive(currentUserId, targetType, targetId, "favorite");
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
     * 当前实现优先保证链路闭环和可读性，先使用逐个汇总的朴素写法。
     *
     * @param currentUserId 当前查看用户 ID
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表
     * @return 以目标 ID 为键的互动汇总映射
     */
    @Override
    public Map<String, InteractionSummary> summaryBatch(long currentUserId, String targetType, List<Long> targetIds) {
        Map<String, InteractionSummary> result = new LinkedHashMap<>();
        if (targetIds == null || targetIds.isEmpty()) {
            return result;
        }
        for (Long targetId : targetIds) {
            if (targetId == null) {
                continue;
            }
            result.put(String.valueOf(targetId), summary(currentUserId, targetType, targetId));
        }
        return result;
    }

    /**
     * 统一处理点赞或收藏状态切换。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型，支持 like / favorite
     * @param active true 表示激活动作，false 表示取消动作
     * @return 动作结果
     */
    private InteractionActionData changeInteraction(
            long currentUserId,
            String targetType,
            long targetId,
            String action,
            boolean active
    ) {
        PostTargetSnapshot snapshot = loadTargetSnapshot(targetType, targetId);
        boolean alreadyActive = socialMapper.existsActiveInteraction(currentUserId, targetType, targetId, action) > 0;
        if (active && alreadyActive) {
            return buildActionData(targetType, targetId, action, true);
        }
        if (!active && !alreadyActive) {
            return buildActionData(targetType, targetId, action, false);
        }

        if (active) {
            if (socialMapper.reactivateInteraction(currentUserId, targetType, targetId, action) == 0) {
                socialMapper.insertInteraction(snowflakeIdGenerator.nextId(), currentUserId, targetType, targetId, action);
            }
        } else {
            socialMapper.deactivateInteraction(currentUserId, targetType, targetId, action);
        }

        String eventType = resolveEventType(action);
        long eventId = snowflakeIdGenerator.nextId();
        socialMapper.insertOutboxEvent(
                eventId,
                "interaction",
                targetId,
                eventType,
                serialize(CounterEventPayload.of(eventId, eventType, targetType, targetId, action, currentUserId))
        );

        int delta = active ? 1 : -1;
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
                        "互动成功后刷新 Redis 视图失败, userId={}, targetType={}, targetId={}, action={}, active={}",
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
     * 构建互动动作返回结果。
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
     * 校验目标内容是否合法并读取快照。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 目标快照
     */
    private PostTargetSnapshot loadTargetSnapshot(String targetType, long targetId) {
        ensureSupportedTargetType(targetType);
        Map<String, Object> row = socialMapper.findPostSnapshot(targetId);
        if (row == null || row.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "目标内容不存在");
        }

        PostTargetSnapshot snapshot = new PostTargetSnapshot(
                toLong(row.get("postId")),
                toLong(row.get("creatorId")),
                toStringValue(row.get("status")),
                toStringValue(row.get("visible"))
        );
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
    private void ensureSupportedTargetType(String targetType) {
        if (!"post".equalsIgnoreCase(targetType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "当前只支持 post 类型");
        }
    }

    /**
     * 读取实体点赞和收藏计数。
     * 优先读 Redis 计数 SDS，缺失时回退到数据库事实层聚合。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 长度为 2 的统计数组，下标 0 为点赞数，下标 1 为收藏数
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
     * Redis 位图未命中时，会回退到数据库事实层判断。
     *
     * @param userId 用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @return 当前是否生效
     */
    private boolean isInteractionActive(long userId, String targetType, long targetId, String action) {
        if (isBitmapMarked(action, targetType, targetId, userId)) {
            return true;
        }
        return socialMapper.existsActiveInteraction(userId, targetType, targetId, action) > 0;
    }

    /**
     * 判断 Redis 位图里是否已标记当前用户动作状态。
     *
     * @param action 动作类型
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param userId 用户 ID
     * @return 位图是否已置位
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
     * 在事务提交后同步位图事实层。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param userId 用户 ID
     * @param action 动作类型
     * @param active true 表示激活，false 表示取消
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
     * 在事务提交后同步实体计数 SDS。
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
     * 解析动作类型对应的事件类型。
     *
     * @param action 动作类型
     * @return 事件类型
     */
    private String resolveEventType(String action) {
        return "like".equals(action) ? "LIKE_CHANGED" : "FAVORITE_CHANGED";
    }

    /**
     * 解析动作类型对应的 Redis 位图指标名。
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
     * @param payload 事件载荷对象
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
            /**
             * 在事务提交完成后执行任务。
             */
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }

    /**
     * 将任意对象转换为 long。
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
     * 将任意对象转换为字符串。
     *
     * @param value 原始对象
     * @return 字符串值
     */
    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 以大端序读取 4 字节无符号整数。
     *
     * @param buffer 原始字节数组
     * @param offset 起始偏移量
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
