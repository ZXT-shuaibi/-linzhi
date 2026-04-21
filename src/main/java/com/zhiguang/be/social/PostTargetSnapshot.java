package com.zhiguang.be.social;

/**
 * 内容目标快照。
 * 用于在社交模块中校验目标内容是否存在、是否可互动以及获取作者 ID。
 */
public class PostTargetSnapshot {

    private final long postId;
    private final long creatorId;
    private final String status;
    private final String visible;

    /**
     * 构造内容目标快照对象。
     *
     * @param postId 内容 ID
     * @param creatorId 作者用户 ID
     * @param status 内容状态
     * @param visible 可见范围
     */
    public PostTargetSnapshot(long postId, long creatorId, String status, String visible) {
        this.postId = postId;
        this.creatorId = creatorId;
        this.status = status;
        this.visible = visible;
    }

    public long getPostId() {
        return postId;
    }

    public long getCreatorId() {
        return creatorId;
    }

    public String getStatus() {
        return status;
    }

    public String getVisible() {
        return visible;
    }

    /**
     * 判断当前内容是否允许进行社交互动。
     *
     * @return 已发布内容返回 true，否则返回 false
     */
    public boolean interactable() {
        return "published".equalsIgnoreCase(status);
    }

    /**
     * 判断当前内容是否为公开可见。
     *
     * @return 公开可见返回 true，否则返回 false
     */
    public boolean isPublicVisible() {
        return "public".equalsIgnoreCase(visible);
    }

    /**
     * 判断当前内容是否为粉丝可见。
     *
     * @return 粉丝可见返回 true，否则返回 false
     */
    public boolean isFollowersVisible() {
        return "followers".equalsIgnoreCase(visible);
    }

    /**
     * 判断当前内容是否为私密可见。
     *
     * @return 私密可见返回 true，否则返回 false
     */
    public boolean isPrivateVisible() {
        return "private".equalsIgnoreCase(visible);
    }
}
