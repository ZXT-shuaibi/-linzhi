package com.zhiguang.be.social.service;

import com.zhiguang.be.social.FollowActionData;
import com.zhiguang.be.social.FollowListData;
import com.zhiguang.be.social.RelationStatusData;

/**
 * 关注关系服务接口。
 * 对外定义关注、取关、列表查询、关系状态以及关注判断能力。
 */
public interface FollowService {

    /**
     * 关注指定用户。
     *
     * @param currentUserId 当前登录用户 ID
     * @param followeeId 被关注用户 ID
     * @return 关注动作结果
     */
    FollowActionData follow(long currentUserId, long followeeId);

    /**
     * 取消关注指定用户。
     *
     * @param currentUserId 当前登录用户 ID
     * @param followeeId 被取消关注用户 ID
     * @return 取消关注动作结果
     */
    FollowActionData unfollow(long currentUserId, long followeeId);

    /**
     * 查询指定用户的关注列表。
     *
     * @param userId 目标用户 ID
     * @param page 页码
     * @param size 分页大小
     * @return 关注列表
     */
    FollowListData following(long userId, int page, int size);

    /**
     * 查询指定用户的粉丝列表。
     *
     * @param userId 目标用户 ID
     * @param page 页码
     * @param size 分页大小
     * @return 粉丝列表
     */
    FollowListData followers(long userId, int page, int size);

    /**
     * 查询当前查看者与目标用户之间的关系状态。
     *
     * @param currentUserId 当前查看者 ID，匿名用户可传入 0
     * @param targetUserId 目标用户 ID
     * @return 关系状态结果
     */
    RelationStatusData relationStatus(long currentUserId, long targetUserId);

    /**
     * 判断一个用户是否已关注另一个用户。
     *
     * @param currentUserId 当前用户 ID
     * @param targetUserId 目标用户 ID
     * @return 已关注返回 true，否则返回 false
     */
    boolean isFollowing(long currentUserId, long targetUserId);
}
