package com.zhiguang.be.discover.model;

import java.util.List;

/**
 * 发现模块附近结果卡片。
 */
public record NearbyItem(
        String id,
        String type,
        String title,
        String summary,
        String coverUrl,
        List<String> tags,
        String authorId,
        String authorName,
        String authorAvatar,
        Double lat,
        Double lng,
        Double distance,
        Long publishTime,
        Integer likeCount,
        Double score
) {
}
