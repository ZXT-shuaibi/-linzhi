package com.zhiguang.be.social.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.social.FollowActionData;
import com.zhiguang.be.social.FollowEventPayload;
import com.zhiguang.be.social.FollowListData;
import com.zhiguang.be.social.FollowUserItem;
import com.zhiguang.be.social.PageMeta;
import com.zhiguang.be.social.SocialRedisKeys;
import com.zhiguang.be.social.mapper.SocialMapper;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private final SocialMapper socialMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final UserSocialCounterService userSocialCounterService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 构造关注服务。
     *
     * @param socialMapper 社交模块 Mapper
     * @param snowflakeIdGenerator 雪花 ID 生成器
     * @param userSocialCounterService 用户维社交计数服务
     * @param stringRedisTemplate Redis 模板
     * @param objectMapper JSON 序列化组件
     */
    public FollowServiceImpl(
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
        validateFollowTarget(currentUserId, followeeId);
        if (socialMapper.existsActiveFollowing(currentUserId, followeeId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "请勿重复关注");
        }

        if (!activateFollowRelation(currentUserId, followeeId)) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "关注关系写入失败");
        }

        long eventId = snowflakeIdGenerator.nextId();
        socialMapper.insertOutboxEvent(
                eventId,
                "follow",
                followeeId,
                "FOLLOW_CREATED",
                serialize(FollowEventPayload.of(eventId, "FOLLOW_CREATED", currentUserId, followeeId))
        );

        runAfterCommit(() -> {
            try {
                long score = System.currentTimeMillis();
                stringRedisTemplate.opsForZSet().add(
                        SocialRedisKeys.followingKey(currentUserId),
                        String.valueOf(followeeId),
                        score
                );
                stringRedisTemplate.opsForZSet().add(
                        SocialRedisKeys.followerKey(followeeId),
                        String.valueOf(currentUserId),
                        score
                );
                stringRedisTemplate.expire(SocialRedisKeys.followingKey(currentUserId), FOLLOW_CACHE_TTL);
                stringRedisTemplate.expire(SocialRedisKeys.followerKey(followeeId), FOLLOW_CACHE_TTL);
                userSocialCounterService.incrementFollowings(currentUserId, 1);
                userSocialCounterService.incrementFollowers(followeeId, 1);
            } catch (Exception ex) {
                log.warn("refresh follow cache failed, followerId={}, followeeId={}", currentUserId, followeeId, ex);
            }
        });

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
        validateFollowTarget(currentUserId, followeeId);

        int affected = socialMapper.cancelFollowing(currentUserId, followeeId);
        if (affected > 0) {
            socialMapper.cancelFollower(followeeId, currentUserId);
            long eventId = snowflakeIdGenerator.nextId();
            socialMapper.insertOutboxEvent(
                    eventId,
                    "follow",
                    followeeId,
                    "FOLLOW_REMOVED",
                    serialize(FollowEventPayload.of(eventId, "FOLLOW_REMOVED", currentUserId, followeeId))
            );

            runAfterCommit(() -> {
                try {
                    stringRedisTemplate.opsForZSet().remove(
                            SocialRedisKeys.followingKey(currentUserId),
                            String.valueOf(followeeId)
                    );
                    stringRedisTemplate.opsForZSet().remove(
                            SocialRedisKeys.followerKey(followeeId),
                            String.valueOf(currentUserId)
                    );
                    userSocialCounterService.incrementFollowings(currentUserId, -1);
                    userSocialCounterService.incrementFollowers(followeeId, -1);
                } catch (Exception ex) {
                    log.warn("refresh unfollow cache failed, followerId={}, followeeId={}", currentUserId, followeeId, ex);
                }
            });
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
    public Map<String, Boolean> relationStatus(long currentUserId, long targetUserId) {
        ensureUserExists(targetUserId, "目标用户不存在");

        Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
        if (currentUserId <= 0L) {
            result.put("following", false);
            result.put("followedBy", false);
            result.put("mutual", false);
            return result;
        }

        ensureUserExists(currentUserId, "当前用户不存在");
        boolean following = isFollowing(currentUserId, targetUserId);
        boolean followedBy = isFollowing(targetUserId, currentUserId);
        result.put("following", following);
        result.put("followedBy", followedBy);
        result.put("mutual", following && followedBy);
        return result;
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
        long followerCount = socialMapper.countFollowerActive(followeeId);
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
                userMap.put(toLong(row.get("userId")), row);
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
                    toStringValue(userRow.get("nickname")),
                    toStringValue(userRow.get("avatar")),
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
            String value = toStringValue(row.get("userId"));
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
                    toStringValue(row.get("userId")),
                    toStringValue(row.get("nickname")),
                    toStringValue(row.get("avatar")),
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
        ensureAuthenticatedUser(currentUserId);
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
     * 激活关注与粉丝双向关系。
     *
     * @param currentUserId 当前用户 ID
     * @param followeeId 目标用户 ID
     * @return 本次是否真的发生了状态变化
     */
    private boolean activateFollowRelation(long currentUserId, long followeeId) {
        boolean followingChanged = activateFollowingRow(currentUserId, followeeId);
        boolean followerChanged = activateFollowerRow(followeeId, currentUserId);
        return followingChanged || followerChanged;
    }

    /**
     * 激活正向关注关系。
     *
     * @param currentUserId 当前用户 ID
     * @param followeeId 目标用户 ID
     * @return 本次是否真的发生了状态变化
     */
    private boolean activateFollowingRow(long currentUserId, long followeeId) {
        if (socialMapper.reactivateFollowing(currentUserId, followeeId) > 0) {
            return true;
        }
        if (socialMapper.existsActiveFollowing(currentUserId, followeeId) > 0) {
            return false;
        }
        try {
            socialMapper.insertFollowing(snowflakeIdGenerator.nextId(), currentUserId, followeeId);
            return true;
        } catch (DuplicateKeyException ex) {
            if (socialMapper.existsActiveFollowing(currentUserId, followeeId) > 0) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 激活反向粉丝关系。
     *
     * @param followeeId 目标用户 ID
     * @param currentUserId 当前用户 ID
     * @return 本次是否真的发生了状态变化
     */
    private boolean activateFollowerRow(long followeeId, long currentUserId) {
        if (socialMapper.reactivateFollower(followeeId, currentUserId) > 0) {
            return true;
        }
        if (socialMapper.existsActiveFollower(followeeId, currentUserId) > 0) {
            return false;
        }
        try {
            socialMapper.insertFollower(snowflakeIdGenerator.nextId(), followeeId, currentUserId);
            return true;
        } catch (DuplicateKeyException ex) {
            if (socialMapper.existsActiveFollower(followeeId, currentUserId) > 0) {
                return false;
            }
            throw ex;
        }
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
