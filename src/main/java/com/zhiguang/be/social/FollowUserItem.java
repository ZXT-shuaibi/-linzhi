package com.zhiguang.be.social;

import java.time.Instant;

/**
 * 关注列表或粉丝列表中的用户条目。
 */
public class FollowUserItem {

    private final String userId;
    private final String nickname;
    private final String avatar;
    private final Instant followedAt;

    /**
     * 构造列表条目对象。
     *
     * @param userId 用户 ID
     * @param nickname 用户昵称
     * @param avatar 用户头像
     * @param followedAt 关注建立时间
     */
    public FollowUserItem(String userId, String nickname, String avatar, Instant followedAt) {
        this.userId = userId;
        this.nickname = nickname;
        this.avatar = avatar;
        this.followedAt = followedAt;
    }

    public String getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public Instant getFollowedAt() {
        return followedAt;
    }
}
