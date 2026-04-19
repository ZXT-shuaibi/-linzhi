package com.zhiguang.be.social;

/**
 * 用户维社交计数数据。
 */
public class UserSocialCounterData {

    private final String userId;
    private final long followings;
    private final long followers;
    private final long posts;
    private final long likedPosts;
    private final long favedPosts;

    /**
     * 构造用户维社交计数对象。
     *
     * @param userId 用户 ID
     * @param followings 关注数
     * @param followers 粉丝数
     * @param posts 已发布内容数
     * @param likedPosts 作者累计获赞数
     * @param favedPosts 作者累计获收藏数
     */
    public UserSocialCounterData(String userId, long followings, long followers, long posts, long likedPosts, long favedPosts) {
        this.userId = userId;
        this.followings = followings;
        this.followers = followers;
        this.posts = posts;
        this.likedPosts = likedPosts;
        this.favedPosts = favedPosts;
    }

    public String getUserId() {
        return userId;
    }

    public long getFollowings() {
        return followings;
    }

    public long getFollowers() {
        return followers;
    }

    public long getPosts() {
        return posts;
    }

    public long getLikedPosts() {
        return likedPosts;
    }

    public long getFavedPosts() {
        return favedPosts;
    }
}
