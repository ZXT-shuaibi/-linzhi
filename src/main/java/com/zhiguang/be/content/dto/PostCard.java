package com.zhiguang.be.content.dto;

import java.time.Instant;
import java.util.List;

/**
 * 文章卡片。
 * 这里保留 coverUrl 作为读模型字段，方便 feed/mine 直接展示封面。
 */
public record PostCard(
        String postId,
        String title,
        String summary,
        String coverUrl,
        List<String> tags,
        PostAuthor author,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean faved,
        String visibility,
        Boolean isTop,
        Instant publishedAt
) {
}
