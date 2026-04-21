package com.zhiguang.be.social;

/**
 * 关注动作返回数据。
 */
public class FollowActionData {

    private final String followeeId;
    private final boolean following;
    private final long followerCount;
    private final long followCount;

    /**
     * 构造关注动作返回对象。
     *
     * @param followeeId 被关注用户 ID
     * @param following 当前是否已关注
     * @param followerCount 目标用户粉丝数
     * @param followCount 当前用户关注数
     */
    public FollowActionData(String followeeId, boolean following, long followerCount, long followCount) {
        this.followeeId = followeeId;
        this.following = following;
        this.followerCount = followerCount;
        this.followCount = followCount;
    }

    public String getFolloweeId() {
        return followeeId;
    }

    public boolean isFollowing() {
        return following;
    }

    public long getFollowerCount() {
        return followerCount;
    }

    public long getFollowCount() {
        return followCount;
    }
}
