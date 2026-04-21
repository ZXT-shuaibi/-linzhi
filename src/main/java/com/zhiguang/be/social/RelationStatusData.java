package com.zhiguang.be.social;

/**
 * 用户关系状态返回数据。
 * 用于统一表达当前查看者与目标用户之间的关注关系。
 */
public class RelationStatusData {

    private final boolean following;
    private final boolean followedBy;
    private final boolean mutual;

    /**
     * 构造关系状态对象。
     *
     * @param following 当前用户是否已关注目标用户
     * @param followedBy 目标用户是否已关注当前用户
     * @param mutual 双方是否互相关注
     */
    public RelationStatusData(boolean following, boolean followedBy, boolean mutual) {
        this.following = following;
        this.followedBy = followedBy;
        this.mutual = mutual;
    }

    public boolean isFollowing() {
        return following;
    }

    public boolean isFollowedBy() {
        return followedBy;
    }

    public boolean isMutual() {
        return mutual;
    }
}
