package com.zhiguang.be.social.service;

import com.zhiguang.be.social.UserSocialCounterData;

/**
 * 用户维社交计数服务接口。
 * 负责维护和重建用户维 SDS 计数。
 */
public interface UserSocialCounterService {

    /**
     * 增量更新关注数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    void incrementFollowings(long userId, int delta);

    /**
     * 增量更新粉丝数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    void incrementFollowers(long userId, int delta);

    /**
     * 增量更新已发布内容数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    void incrementPosts(long userId, int delta);

    /**
     * 增量更新作者累计获赞数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    void incrementLikesReceived(long userId, int delta);

    /**
     * 增量更新作者累计获收藏数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    void incrementFavoritesReceived(long userId, int delta);

    /**
     * 查询用户维社交计数。
     *
     * @param userId 用户 ID
     * @return 用户维计数
     */
    UserSocialCounterData getUserSocialCounter(long userId);

    /**
     * 基于事实层重建用户维社交计数。
     *
     * @param userId 用户 ID
     * @return 重建后的用户维计数
     */
    UserSocialCounterData rebuildAllCounters(long userId);
}
