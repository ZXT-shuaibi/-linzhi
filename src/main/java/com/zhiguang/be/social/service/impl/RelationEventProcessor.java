package com.zhiguang.be.social.service.impl;

import com.zhiguang.be.social.FollowEventPayload;
import com.zhiguang.be.social.SocialRedisKeys;
import com.zhiguang.be.social.mapper.SocialMapper;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 关系事件投影处理器。
 * following 是唯一可信主表，本处理器负责把 outbox 事件异步投影到 follower 表、Redis 列表缓存和用户维计数。
 */
@Service
public class RelationEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(RelationEventProcessor.class);
    private static final Duration FOLLOW_CACHE_TTL = Duration.ofHours(2);

    private final SocialMapper socialMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserSocialCounterService userSocialCounterService;
    private final Duration dedupTtl;

    /**
     * 构造关系事件投影处理器。
     *
     * @param socialMapper 社交模块 Mapper
     * @param stringRedisTemplate Redis 模板
     * @param userSocialCounterService 用户维计数服务
     * @param dedupTtlMinutes 事件去重 TTL，避免 Kafka 重复投递造成重复投影
     */
    public RelationEventProcessor(
            SocialMapper socialMapper,
            StringRedisTemplate stringRedisTemplate,
            UserSocialCounterService userSocialCounterService,
            @Value("${social.relation.outbox.dedup-ttl-minutes:10}") long dedupTtlMinutes
    ) {
        this.socialMapper = socialMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.userSocialCounterService = userSocialCounterService;
        this.dedupTtl = Duration.ofMinutes(Math.max(1L, dedupTtlMinutes));
    }

    /**
     * 处理关注关系事件。
     * 该方法可以被 Kafka 消费器调用，也可以在本地开发模式下由关注事务提交后直接调用。
     *
     * @param event 关注事件载荷
     */
    public void process(FollowEventPayload event) {
        if (event == null || event.getEventType() == null) {
            return;
        }

        long followerId = parseRequiredId(event.getFollowerId(), "关注者 ID");
        long followeeId = parseRequiredId(event.getFolloweeId(), "被关注者 ID");
        String eventId = event.getEventId() == null ? "0" : event.getEventId();
        String eventType = event.getEventType();

        String dedupKey = SocialRedisKeys.relationEventDedupKey(eventType, followerId, followeeId, eventId);
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(
                dedupKey,
                "1",
                dedupTtl
        );
        if (!Boolean.TRUE.equals(first)) {
            return;
        }

        try {
            if (isFollowCreated(eventType)) {
                projectFollowCreated(event, followerId, followeeId);
                return;
            }
            if (isFollowRemoved(eventType)) {
                projectFollowRemoved(followerId, followeeId);
            }
        } catch (RuntimeException ex) {
            stringRedisTemplate.delete(dedupKey);
            throw ex;
        }
    }

    /**
     * 投影关注成功事件。
     *
     * @param event 关注事件
     * @param followerId 关注者 ID
     * @param followeeId 被关注者 ID
     */
    private void projectFollowCreated(FollowEventPayload event, long followerId, long followeeId) {
        if (socialMapper.existsActiveFollowing(followerId, followeeId) <= 0) {
            return;
        }
        boolean changed = activateFollowerProjection(resolveRelationId(event), followeeId, followerId);
        refreshFollowCache(followerId, followeeId, true);
        if (changed) {
            userSocialCounterService.incrementFollowings(followerId, 1);
            userSocialCounterService.incrementFollowers(followeeId, 1);
        }
    }

    /**
     * 投影取消关注事件。
     *
     * @param followerId 关注者 ID
     * @param followeeId 被关注者 ID
     */
    private void projectFollowRemoved(long followerId, long followeeId) {
        if (socialMapper.existsActiveFollowing(followerId, followeeId) > 0) {
            return;
        }
        int affected = socialMapper.cancelFollower(followeeId, followerId);
        refreshFollowCache(followerId, followeeId, false);
        if (affected > 0) {
            userSocialCounterService.incrementFollowings(followerId, -1);
            userSocialCounterService.incrementFollowers(followeeId, -1);
        }
    }

    /**
     * 激活 follower 投影表中的粉丝关系。
     *
     * @param relationId following 主表关系 ID
     * @param followeeId 被关注者 ID
     * @param followerId 关注者 ID
     * @return 本次是否真实改变了投影状态
     */
    private boolean activateFollowerProjection(long relationId, long followeeId, long followerId) {
        if (socialMapper.reactivateFollower(followeeId, followerId) > 0) {
            return true;
        }
        if (socialMapper.existsActiveFollower(followeeId, followerId) > 0) {
            return false;
        }
        try {
            socialMapper.insertFollower(relationId, followeeId, followerId);
            return true;
        } catch (DuplicateKeyException ex) {
            if (socialMapper.existsActiveFollower(followeeId, followerId) > 0) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 刷新关注与粉丝列表缓存。
     *
     * @param followerId 关注者 ID
     * @param followeeId 被关注者 ID
     * @param active true 表示关注，false 表示取消关注
     */
    private void refreshFollowCache(long followerId, long followeeId, boolean active) {
        try {
            String followingKey = SocialRedisKeys.followingKey(followerId);
            String followerKey = SocialRedisKeys.followerKey(followeeId);
            if (active) {
                long score = System.currentTimeMillis();
                stringRedisTemplate.opsForZSet().add(followingKey, String.valueOf(followeeId), score);
                stringRedisTemplate.opsForZSet().add(followerKey, String.valueOf(followerId), score);
            } else {
                stringRedisTemplate.opsForZSet().remove(followingKey, String.valueOf(followeeId));
                stringRedisTemplate.opsForZSet().remove(followerKey, String.valueOf(followerId));
            }
            stringRedisTemplate.expire(followingKey, FOLLOW_CACHE_TTL);
            stringRedisTemplate.expire(followerKey, FOLLOW_CACHE_TTL);
        } catch (Exception ex) {
            log.warn("刷新关系投影缓存失败，followerId={}, followeeId={}", followerId, followeeId, ex);
        }
    }

    /**
     * 解析投影表使用的关系 ID。
     *
     * @param event 关注事件
     * @return 关系 ID
     */
    private long resolveRelationId(FollowEventPayload event) {
        String relationId = event.getRelationId();
        if (relationId != null && !relationId.isBlank()) {
            return parseRequiredId(relationId, "关系 ID");
        }
        return parseRequiredId(event.getEventId(), "事件 ID");
    }

    /**
     * 解析必填长整型 ID。
     *
     * @param value 原始值
     * @param name 字段名
     * @return 长整型 ID
     */
    private long parseRequiredId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return Long.parseLong(value);
    }

    /**
     * 判断是否为关注创建事件。
     *
     * @param eventType 事件类型
     * @return 是关注创建事件返回 true
     */
    private boolean isFollowCreated(String eventType) {
        return "FOLLOW_CREATED".equals(eventType) || "FollowCreated".equals(eventType);
    }

    /**
     * 判断是否为取消关注事件。
     *
     * @param eventType 事件类型
     * @return 是取消关注事件返回 true
     */
    private boolean isFollowRemoved(String eventType) {
        return "FOLLOW_REMOVED".equals(eventType)
                || "FOLLOW_CANCELED".equals(eventType)
                || "FollowCanceled".equals(eventType);
    }
}
