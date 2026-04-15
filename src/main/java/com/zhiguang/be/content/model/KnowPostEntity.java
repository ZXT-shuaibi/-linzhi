package com.zhiguang.be.content.model;

import java.time.Instant;

/**
 * 文章实体。
 */
public record KnowPostEntity(
        String postId,
        String creatorId,
        Long tagId,
        String tagsJson,
        String title,
        String description,
        Double latitude,
        Double longitude,
        String geoHash,
        String address,
        String contentUrl,
        String contentObjectKey,
        String contentEtag,
        Long contentSize,
        String contentSha256,
        Boolean isTop,
        String type,
        String visible,
        String imgUrlsJson,
        String videoUrl,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant publishTime
) {
}
