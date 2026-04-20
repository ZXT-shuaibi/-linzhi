package com.zhiguang.be.content.model;

import java.time.Instant;

/**
 * 文章详情查询行对象。
 */
public record KnowPostDetailRow(
        String postId,
        String creatorId,
        String authorNickname,
        String authorAvatar,
        String status,
        String title,
        String description,
        String contentUrl,
        String imgUrlsJson,
        String tagsJson,
        String visible,
        String type,
        Boolean isTop,
        Double latitude,
        Double longitude,
        String geoHash,
        String address,
        Instant publishTime,
        Instant createdAt,
        Instant updatedAt
) {
}
