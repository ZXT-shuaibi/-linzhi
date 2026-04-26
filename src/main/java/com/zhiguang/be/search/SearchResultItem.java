package com.zhiguang.be.search;

import java.time.Instant;
import java.util.List;

/**
 * 搜索结果卡片。
 */
public record SearchResultItem(
        String postId,
        String title,
        String summary,
        String coverUrl,
        List<String> tags,
        String authorId,
        String authorNickname,
        String authorAvatar,
        String authorTagJson,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean faved,
        Boolean isTop,
        Instant publishedAt,
        Double score,
        Double distanceMeters,
        List<String> searchAfter
) {
}
