package com.zhiguang.be.social.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.common.tx.Transactions;
import com.zhiguang.be.common.util.Numbers;
import com.zhiguang.be.social.FollowActionData;
import com.zhiguang.be.social.FollowEventPayload;
import com.zhiguang.be.social.FollowListData;
import com.zhiguang.be.social.FollowUserItem;
import com.zhiguang.be.social.PageMeta;
import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.SocialRedisKeys;
import com.zhiguang.be.social.mapper.SocialMapper;
import com.zhiguang.be.social.service.FollowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 社交关注服务实现。
 * 对齐当前项目的关注、取关、列表查询、关系态查询与缓存维护逻辑。
 */
@Service
public class FollowServiceImpl implements FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowServiceImpl.class);

    private static final Duration FOLLOW_CACHE_TTL = Duration.ofHours(2);
    private static final String FOLLOW_RATE_LIMIT_LUA =
            "local key = KEYS[1]\n"
                    + "local capacity = tonumber(ARGV[1])\n"
                    + "local rate = tonumber(ARGV[2])\n"
                    + "local now = tonumber(redis.call('TIME')[1])\n"
                    + "local last = tonumber(redis.call('HGET', key, 'last') or now)\n"
                    + "local tokens = tonumber(redis.call('HGET', key, 'tokens') or capacity)\n"
                    + "local elapsed = math.max(0, now - last)\n"
                    + "tokens = math.min(capacity, tokens + elapsed * rate)\n"
                    + "if tokens < 1 then\n"
                    + "  redis.call('HSET', key, 'last', now, 'tokens', tokens)\n"
                    + "  redis.call('PEXPIRE', key, 60000)\n"
                    + "  return 0\n"
                    + "end\n"
                    + "tokens = tokens - 1\n"
                    + "redis.call('HSET', key, 'last', now, 'tokens', tokens)\n"
                    + "redis.call('PEXPIRE', key, 60000)\n"
                    + "return 1\n";

    private final SocialMapper socialMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final RelationEventProcessor relationEventProcessor;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean localProjectionEnabled;
    private final int followRateLimitCapacity;
    private final int followRateLimitRefillPerSecond;
    private final DefaultRedisScript<Long> followRateLimitScript;

    /**
     * 构造关注服务。
     *
     * @param socialMapper 社交模块 Mapper
     * @param snowflakeIdGenerator 雪花 ID 生成器
     * @param relationEventProcessor 关系事件投影处理器
     * @param stringRedisTemplate Redis 模板
     * @param objectMapper JSON 序列化组件
     * @param localProjectionEnabled 是否启用本地投影兜底
     * @param followRateLimitCapacity 关注令牌桶容量
     * @param followRateLimitRefillPerSecond 关注令牌桶每秒恢复数量
     */
    public FollowServiceImpl(
            SocialMapper socialMapper,
            SnowflakeIdGenerator snowflakeIdGenerator,
            RelationEventProcessor relationEventProcessor,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${social.relation.outbox.local-projection-enabled:true}") boolean localProjectionEnabled,
            @Value("${social.relation.rate-limit.capacity:100}") int followRateLimitCapacity,
            @Value("${social.relation.rate-limit.refill-per-second:1}") int followRateLimitRefillPerSecond
    ) {
        this.socialMapper = socialMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.relationEventProcessor = relationEventProcessor;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.localProjectionEnabled = localProjectionEnabled;
        this.followRateLimitCapacity = Math.max(1, followRateLimitCapacity);
        this.followRateLimitRefillPerSecond = Math.max(1, followRateLimitRefillPerSecond);
        this.followRateLimitScript = new DefaultRedisScript<Long>();
        this.followRateLimitScript.setResultType(Long.class);
        this.followRateLimitScript.setScriptText(FOLLOW_RATE_LIMIT_LUA);
    }

    /**
     * 关注目标用户。
     *
     * @param currentUserId 当前登录用户 ID
     * @param followeeId 目标用户 ID
     * @return 关注动作结果
     */
    @Override
    @Transactional
    public FollowActionData follow(long currentUserId, long followeeId) {
        SocialServiceSupport.ensureAuthenticatedUser(currentUserId);
        enforceFollowRateLimit(currentUserId);
        validateFollowTarget(currentUserId, followeeId);
        if (socialMapper.existsActiveFollowing(currentUserId, followeeId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "请勿重复关注");
        }

        long relationId = activateFollowRelation(currentUserId, followeeId);
        if (relationId <= 0L) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "关注关系写入失败");
        }

        long eventId = snowflakeIdGenerator.nextId();
        FollowEventPayload payload = FollowEventPayload.of(eventId, "FOLLOW_CREATED", currentUserId, followeeId, relationId);
        socialMapper.insertOutboxEvent(
                eventId,
                "following",
                relationId,
                "FOLLOW_CREATED",
                SocialServiceSupport.serialize(objectMapper, payload)
        );

        Transactions.runAfterCommit(() -> projectRelationEvent(payload));

        return buildFollowActionData(currentUserId, followeeId, true);
    }

    /**
     * 取消关注目标用户。
     *
     * @param currentUserId 当前登录用户 ID
     * @param followeeId 目标用户 ID
     * @return 取消关注后的动作结果
     */
    @Override
    @Transactional
    public FollowActionData unfollow(long currentUserId, long followeeId) {
        enforceFollowRateLimit(currentUserId);
        validateFollowTarget(currentUserId, followeeId);

        Long relationId = socialMapper.findFollowingRelationId(currentUserId, followeeId);
        int affected = socialMapper.cancelFollowing(currentUserId, followeeId);
        if (affected > 0) {
            long eventId = snowflakeIdGenerator.nextId();
            FollowEventPayload payload = FollowEventPayload.of(
                    eventId,
                    "FOLLOW_REMOVED",
                    currentUserId,
                    followeeId,
                    relationId == null ? 0L : relationId
            );
            socialMapper.insertOutboxEvent(
                    eventId,
                    "following",
                    relationId,
                    "FOLLOW_REMOVED",
                    SocialServiceSupport.serialize(objectMapper, payload)
            );

            Transactions.runAfterCommit(() -> projectRelationEvent(payload));
        }

        return buildFollowActionData(currentUserId, followeeId, false);
    }

    /**
     * 查询指定用户的关注列表。
     *
     * @param userId 目标用户 ID
     * @param page 页码
     * @param size 每页大小
     * @return 关注列表
     */
    @Override
    public FollowListData following(long userId, int page, int size) {
        ensureUserExists(userId, "目标用户不存在");
        return buildFollowListData(userId, page, size, true);
    }

    /**
     * 查询指定用户的粉丝列表。
     *
     * @param userId 目标用户 ID
     * @param page 页码
     * @param size 每页大小
     * @return 粉丝列表
     */
    @Override
    public FollowListData followers(long userId, int page, int size) {
        ensureUserExists(userId, "目标用户不存在");
        return buildFollowListData(userId, page, size, false);
    }

    /**
     * 查询当前查看者与目标用户的关系态。
     *
     * @param currentUserId 当前查看者用户 ID，匿名时可传 0
     * @param targetUserId 目标用户 ID
     * @return 关注三态结果
     */
    @Override
    public RelationStatusData relationStatus(long currentUserId, long targetUserId) {
        ensureUserExists(targetUserId, "目标用户不存在");

        if (currentUserId <= 0L) {
            return new RelationStatusData(false, false, false);
        }

        ensureUserExists(currentUserId, "当前用户不存在");
        boolean following = isFollowing(currentUserId, targetUserId);
        boolean followedBy = isFollowing(targetUserId, currentUserId);
        return new RelationStatusData(following, followedBy, following && followedBy);
    }

    /**
     * 判断一个用户是否已关注另一个用户。
     *
     * @param currentUserId 当前用户 ID
     * @param targetUserId 目标用户 ID
     * @return 已关注返回 true，否则返回 false
     */
    @Override
    public boolean isFollowing(long currentUserId, long targetUserId) {
        if (currentUserId <= 0L || targetUserId <= 0L) {
            return false;
        }
        return socialMapper.existsActiveFollowing(currentUserId, targetUserId) > 0;
    }

    /**
     * 构造关注动作返回结果。
     *
     * @param currentUserId 当前用户 ID
     * @param followeeId 目标用户 ID
     * @param following 当前是否关注
     * @return 关注动作结果
     */
    private FollowActionData buildFollowActionData(long currentUserId, long followeeId, boolean following) {
        long followerCount = socialMapper.countFollowersFromFollowing(followeeId);
        long followCount = socialMapper.countFollowingActive(currentUserId);
        return new FollowActionData(String.valueOf(followeeId), following, followerCount, followCount);
    }

    /**
     * 统一构造关注列表或粉丝列表结果。
     *
     * @param userId 目标用户 ID
     * @param page 页码
     * @param size 每页大小
     * @param followingMode true 表示关注列表，false 表示粉丝列表
     * @return 列表结果
     */
    private FollowListData buildFollowListData(long userId, int page, int size, boolean followingMode) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size, 50), 1);
        int offset = (safePage - 1) * safeSize;
        long total = followingMode
                ? socialMapper.countFollowingActive(userId)
                : socialMapper.countFollowerActive(userId);
        long remain = Math.max(total - offset, 0L);

        List<FollowUserItem> items = readFollowItemsFromCache(userId, offset, safeSize, followingMode);
        long expected = Math.min((long) safeSize, remain);
        if (items.size() < expected) {
            warmFollowCache(userId, offset + safeSize, followingMode);
            items = readFollowItemsFromCache(userId, offset, safeSize, followingMode);
        }
        if (items.size() < expected) {
            items = queryFollowItemsFromDb(userId, safeSize, offset, followingMode);
        }

        return new FollowListData(items, PageMeta.of(safePage, safeSize, total));
    }

    /**
     * 从 Redis 读取关注或粉丝列表缓存。
     *
     * @param userId 目标用户 ID
     * @param offset 偏移量
     * @param size 返回数量
     * @param followingMode true 表示关注列表，false 表示粉丝列表
     * @return 列表条目
     */
    private List<FollowUserItem> readFollowItemsFromCache(long userId, int offset, int size, boolean followingMode) {
        String key = followingMode ? SocialRedisKeys.followingKey(userId) : SocialRedisKeys.followerKey(userId);
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(key, offset, offset + size - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = new ArrayList<Long>(tuples.size());
        Map<Long, Instant> followedAtMap = new LinkedHashMap<Long, Instant>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple == null || tuple.getValue() == null) {
                continue;
            }
            try {
                long targetUserId = Long.parseLong(tuple.getValue());
                ids.add(targetUserId);
                double score = tuple.getScore() == null ? System.currentTimeMillis() : tuple.getScore();
                followedAtMap.put(targetUserId, Instant.ofEpochMilli((long) score));
            } catch (Exception ignore) {
                // 忽略异常缓存值，继续读取其它记录。
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> userRows = socialMapper.listUsersByIds(ids);
        Map<Long, Map<String, Object>> userMap = new LinkedHashMap<Long, Map<String, Object>>();
        if (userRows != null) {
            for (Map<String, Object> row : userRows) {
                userMap.put(Numbers.toLong(row.get("userId")), row);
            }
        }

        List<FollowUserItem> items = new ArrayList<FollowUserItem>(ids.size());
        for (Long id : ids) {
            Map<String, Object> userRow = userMap.get(id);
            if (userRow == null) {
                continue;
            }
            items.add(new FollowUserItem(
                    String.valueOf(id),
                    SocialServiceSupport.toStringValue(userRow.get("nickname")),
                    SocialServiceSupport.toStringValue(userRow.get("avatar")),
                    followedAtMap.getOrDefault(id, Instant.now())
            ));
        }
        return items;
    }

    /**
     * 使用数据库结果预热 Redis 列表缓存。
     *
     * @param userId 目标用户 ID
     * @param need 需要预热的数量
     * @param followingMode true 表示关注列表，false 表示粉丝列表
     */
    private void warmFollowCache(long userId, int need, boolean followingMode) {
        int safeNeed = Math.max(need, 1);
        List<Map<String, Object>> rows = followingMode
                ? socialMapper.listFollowingItems(userId, safeNeed, 0)
                : socialMapper.listFollowerItems(userId, safeNeed, 0);
        if (rows == null || rows.isEmpty()) {
            return;
        }

        String key = followingMode ? SocialRedisKeys.followingKey(userId) : SocialRedisKeys.followerKey(userId);
        for (Map<String, Object> row : rows) {
            String value = SocialServiceSupport.toStringValue(row.get("userId"));
            if (value == null) {
                continue;
            }
            Instant followedAt = toInstant(row.get("followedAt"));
            stringRedisTemplate.opsForZSet().add(key, value, followedAt.toEpochMilli());
        }
        stringRedisTemplate.expire(key, FOLLOW_CACHE_TTL);
    }

    /**
     * 直接从数据库查询关注或粉丝列表。
     *
     * @param userId 目标用户 ID
     * @param size 返回数量
     * @param offset 偏移量
     * @param followingMode true 表示关注列表，false 表示粉丝列表
     * @return 列表条目
     */
    private List<FollowUserItem> queryFollowItemsFromDb(long userId, int size, int offset, boolean followingMode) {
        List<Map<String, Object>> rows = followingMode
                ? socialMapper.listFollowingItems(userId, size, offset)
                : socialMapper.listFollowerItems(userId, size, offset);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<FollowUserItem> items = new ArrayList<FollowUserItem>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(new FollowUserItem(
                    SocialServiceSupport.toStringValue(row.get("userId")),
                    SocialServiceSupport.toStringValue(row.get("nickname")),
                    SocialServiceSupport.toStringValue(row.get("avatar")),
                    toInstant(row.get("followedAt"))
            ));
        }
        return items;
    }

    /**
     * 校验关注目标是否合法。
     *
     * @param currentUserId 当前登录用户 ID
     * @param followeeId 目标用户 ID
     */
    private void validateFollowTarget(long currentUserId, long followeeId) {
        SocialServiceSupport.ensureAuthenticatedUser(currentUserId);
        ensureUserExists(currentUserId, "当前用户不存在");
        if (currentUserId == followeeId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "不能关注自己");
        }
        ensureUserExists(followeeId, "目标用户不存在");
    }

    /**
     * 校验指定用户是否存在。
     *
     * @param userId 用户 ID
     * @param message 不存在时的错误消息
     */
    private void ensureUserExists(long userId, String message) {
        if (socialMapper.existsUser(userId) <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, message);
        }
    }

    /**
     * 使用 Redis Lua 令牌桶限制关注接口调用频率。
     *
     * @param currentUserId 当前操作用户 ID
     */
    private void enforceFollowRateLimit(long currentUserId) {
        try {
            Long allowed = stringRedisTemplate.execute(
                    followRateLimitScript,
                    Collections.singletonList("rl:follow:" + currentUserId),
                    String.valueOf(followRateLimitCapacity),
                    String.valueOf(followRateLimitRefillPerSecond)
            );
            if (allowed == null || allowed == 0L) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "关注/取关操作过于频繁，请稍后再试");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("关注限流检查失败，采用放行策略，userId={}", currentUserId, ex);
        }
    }

    /**
     * 激活正向关注关系。
     * 主写路径只维护 following 主表，follower、缓存和计数由 outbox 事件异步投影。
     *
     * @param currentUserId 当前用户 ID
     * @param followeeId 目标用户 ID
     * @return following 主表关系 ID
     */
    private long activateFollowRelation(long currentUserId, long followeeId) {
        return activateFollowingRow(currentUserId, followeeId);
    }

    /**
     * 激活正向关注关系。
     *
     * @param currentUserId 当前用户 ID
     * @param followeeId 目标用户 ID
     * @return following 主表关系 ID；未发生变化时返回 0
     */
    private long activateFollowingRow(long currentUserId, long followeeId) {
        if (socialMapper.reactivateFollowing(currentUserId, followeeId) > 0) {
            Long relationId = socialMapper.findFollowingRelationId(currentUserId, followeeId);
            return relationId == null ? 0L : relationId;
        }
        if (socialMapper.existsActiveFollowing(currentUserId, followeeId) > 0) {
            return 0L;
        }
        try {
            long relationId = snowflakeIdGenerator.nextId();
            socialMapper.insertFollowing(relationId, currentUserId, followeeId);
            return relationId;
        } catch (DuplicateKeyException ex) {
            if (socialMapper.existsActiveFollowing(currentUserId, followeeId) > 0) {
                return 0L;
            }
            throw ex;
        }
    }

    /**
     * 在本地开发模式下直接执行 outbox 投影。
     * 真实 Canal + Kafka 模式可关闭该兜底，让 Kafka 消费器负责投影。
     *
     * @param payload 关注事件载荷
     */
    private void projectRelationEvent(FollowEventPayload payload) {
        if (!localProjectionEnabled) {
            return;
        }
        try {
            relationEventProcessor.process(payload);
        } catch (Exception ex) {
            log.warn("本地关系投影失败，eventId={}, eventType={}",
                    payload.getEventId(), payload.getEventType(), ex);
        }
    }

    /**
     * 将数据库时间对象转换为 Instant。
     *
     * @param value 原始时间对象
     * @return Instant 时间
     */
    private Instant toInstant(Object value) {
        if (value instanceof Instant) {
            return (Instant) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toInstant();
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).atZone(ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof Number) {
            return Instant.ofEpochMilli(((Number) value).longValue());
        }
        return Instant.now();
    }
}
