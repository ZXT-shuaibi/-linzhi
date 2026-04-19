package com.zhiguang.be.social.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 社交模块统一数据访问接口。
 * 负责关注关系、互动记录、帖子快照、用户基础信息与 outbox 事件写入。
 */
@Mapper
public interface SocialMapper {

    /**
     * 判断指定用户是否存在。
     *
     * @param userId 用户 ID
     * @return 命中数量
     */
    int existsUser(@Param("userId") long userId);

    /**
     * 查询帖子快照信息。
     *
     * @param postId 帖子 ID
     * @return 帖子快照字段映射
     */
    Map<String, Object> findPostSnapshot(@Param("postId") long postId);

    /**
     * 判断是否已存在有效关注关系。
     *
     * @param fromUserId 关注者用户 ID
     * @param toUserId 被关注者用户 ID
     * @return 命中数量
     */
    int existsActiveFollowing(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId);

    /**
     * 恢复历史关注关系为有效状态。
     *
     * @param fromUserId 关注者用户 ID
     * @param toUserId 被关注者用户 ID
     * @return 影响行数
     */
    int reactivateFollowing(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId);

    /**
     * 插入新的关注关系。
     *
     * @param id 关系 ID
     * @param fromUserId 关注者用户 ID
     * @param toUserId 被关注者用户 ID
     * @return 影响行数
     */
    int insertFollowing(@Param("id") long id, @Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId);

    /**
     * 将关注关系改为取消状态。
     *
     * @param fromUserId 关注者用户 ID
     * @param toUserId 被关注者用户 ID
     * @return 影响行数
     */
    int cancelFollowing(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId);

    /**
     * 恢复历史粉丝关系为有效状态。
     *
     * @param toUserId 被关注者用户 ID
     * @param fromUserId 关注者用户 ID
     * @return 影响行数
     */
    int reactivateFollower(@Param("toUserId") long toUserId, @Param("fromUserId") long fromUserId);

    /**
     * 插入新的粉丝关系。
     *
     * @param id 关系 ID
     * @param toUserId 被关注者用户 ID
     * @param fromUserId 关注者用户 ID
     * @return 影响行数
     */
    int insertFollower(@Param("id") long id, @Param("toUserId") long toUserId, @Param("fromUserId") long fromUserId);

    /**
     * 将粉丝关系改为取消状态。
     *
     * @param toUserId 被关注者用户 ID
     * @param fromUserId 关注者用户 ID
     * @return 影响行数
     */
    int cancelFollower(@Param("toUserId") long toUserId, @Param("fromUserId") long fromUserId);

    /**
     * 查询关注列表条目。
     *
     * @param userId 用户 ID
     * @param limit 返回数量
     * @param offset 偏移量
     * @return 列表条目字段映射
     */
    List<Map<String, Object>> listFollowingItems(
            @Param("userId") long userId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * 查询粉丝列表条目。
     *
     * @param userId 用户 ID
     * @param limit 返回数量
     * @param offset 偏移量
     * @return 列表条目字段映射
     */
    List<Map<String, Object>> listFollowerItems(
            @Param("userId") long userId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * 批量查询用户基础信息。
     *
     * @param userIds 用户 ID 列表
     * @return 用户字段映射列表
     */
    List<Map<String, Object>> listUsersByIds(@Param("userIds") List<Long> userIds);

    /**
     * 统计有效关注数。
     *
     * @param fromUserId 用户 ID
     * @return 关注数
     */
    long countFollowingActive(@Param("fromUserId") long fromUserId);

    /**
     * 统计有效粉丝数。
     *
     * @param toUserId 用户 ID
     * @return 粉丝数
     */
    long countFollowerActive(@Param("toUserId") long toUserId);

    /**
     * 统计用户已发布帖子数。
     *
     * @param creatorId 作者用户 ID
     * @return 已发布帖子数
     */
    long countPublishedPostsByCreatorId(@Param("creatorId") long creatorId);

    /**
     * 查询用户已发布帖子 ID 列表。
     *
     * @param creatorId 作者用户 ID
     * @return 帖子 ID 列表
     */
    List<Long> listPublishedPostIdsByCreatorId(@Param("creatorId") long creatorId);

    /**
     * 判断是否存在有效互动关系。
     *
     * @param userId 用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param actionType 动作类型
     * @return 命中数量
     */
    int existsActiveInteraction(
            @Param("userId") long userId,
            @Param("targetType") String targetType,
            @Param("targetId") long targetId,
            @Param("actionType") String actionType
    );

    /**
     * 恢复历史互动关系为有效状态。
     *
     * @param userId 用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param actionType 动作类型
     * @return 影响行数
     */
    int reactivateInteraction(
            @Param("userId") long userId,
            @Param("targetType") String targetType,
            @Param("targetId") long targetId,
            @Param("actionType") String actionType
    );

    /**
     * 插入新的互动关系。
     *
     * @param id 互动记录 ID
     * @param userId 用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param actionType 动作类型
     * @return 影响行数
     */
    int insertInteraction(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("targetType") String targetType,
            @Param("targetId") long targetId,
            @Param("actionType") String actionType
    );

    /**
     * 将互动关系改为取消状态。
     *
     * @param userId 用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param actionType 动作类型
     * @return 影响行数
     */
    int deactivateInteraction(
            @Param("userId") long userId,
            @Param("targetType") String targetType,
            @Param("targetId") long targetId,
            @Param("actionType") String actionType
    );

    /**
     * 聚合统计某类互动的有效数量。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param actionType 动作类型
     * @return 聚合数量
     */
    Long aggregateActiveInteractionCount(
            @Param("targetType") String targetType,
            @Param("targetId") long targetId,
            @Param("actionType") String actionType
    );

    /**
     * 写入一条 outbox 事件。
     *
     * @param id 事件 ID
     * @param aggregateType 聚合类型
     * @param aggregateId 聚合 ID
     * @param eventType 事件类型
     * @param payload 事件载荷 JSON
     * @return 影响行数
     */
    int insertOutboxEvent(
            @Param("id") long id,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") Long aggregateId,
            @Param("eventType") String eventType,
            @Param("payload") String payload
    );
}
