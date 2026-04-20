package com.zhiguang.be.feed;

import com.zhiguang.be.content.dto.PostAuthor;

import java.time.Instant;

/**
 * 首页 Feed 单条卡片。
 * 第一版保持轻量，只返回首页真正需要的基础浏览字段。
 */
public record FeedItem(
        String postId,
        String title,
        String summary,
        PostAuthor author,
        Double distanceMeters,
        Double hotScore,
        Instant publishedAt
) {
}
