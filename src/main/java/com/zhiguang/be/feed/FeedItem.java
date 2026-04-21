package com.zhiguang.be.feed;

import com.zhiguang.be.content.dto.PostAuthor;

import java.time.Instant;
import java.util.List;

/**
 * 首页 Feed 单条卡片。
 * 参考 zhiguang 的卡片结构，保留首页真正需要的展示字段与互动汇总字段。
 */
public record FeedItem(
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
        Double distanceMeters,
        Double hotScore,
        Boolean isTop,
        Instant publishedAt
) {
}
