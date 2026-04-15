package com.zhiguang.be.content.dto;

import java.time.Instant;
import java.util.List;

/**
 * 文章详情。
 * 封面图由前端或读模型从 imageUrls 首图推导，不再单独存储 coverUrl。
 */
public record PostDetail(
        String postId,
        PostAuthor author,
        String status,
        String title,
        String summary,
        String contentUrl,
        List<String> imageUrls,
        List<String> tags,
        PostLocation location,
        String visibility,
        String type,
        Boolean isTop,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
