package com.zhiguang.be.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 发现模块附近结果卡片。
 */
public record NearbyItem(
        String id,
        @JsonProperty("entityType")
        String type,
        String title,
        String summary,
        String coverUrl,
        String address,
        List<String> tags,
        String authorId,
        String authorName,
        String authorAvatar,
        Double lat,
        Double lng,
        Double distance,
        Long publishTime,
        Integer likeCount,
        Integer favoriteCount,
        Double score
) {
}
