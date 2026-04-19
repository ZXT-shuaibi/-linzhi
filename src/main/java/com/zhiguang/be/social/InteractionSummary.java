package com.zhiguang.be.social;

/**
 * 互动汇总数据。
 */
public class InteractionSummary {

    private final String targetType;
    private final String targetId;
    private final long likeCount;
    private final long favoriteCount;
    private final boolean viewerLiked;
    private final boolean viewerFavorited;

    /**
     * 构造互动汇总对象。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param likeCount 点赞数
     * @param favoriteCount 收藏数
     * @param viewerLiked 当前查看者是否已点赞
     * @param viewerFavorited 当前查看者是否已收藏
     */
    public InteractionSummary(
            String targetType,
            String targetId,
            long likeCount,
            long favoriteCount,
            boolean viewerLiked,
            boolean viewerFavorited
    ) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.likeCount = likeCount;
        this.favoriteCount = favoriteCount;
        this.viewerLiked = viewerLiked;
        this.viewerFavorited = viewerFavorited;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public long getFavoriteCount() {
        return favoriteCount;
    }

    public boolean isViewerLiked() {
        return viewerLiked;
    }

    public boolean isViewerFavorited() {
        return viewerFavorited;
    }
}
