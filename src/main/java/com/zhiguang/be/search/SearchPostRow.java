package com.zhiguang.be.search;

import java.time.Instant;

/**
 * 搜索结果原始行。
 */
public record SearchPostRow(
        String postId,
        String title,
        String summary,
        String tagsJson,
        String imgUrlsJson,
        String authorId,
        String authorNickname,
        String authorAvatar,
        String authorTagJson,
        Integer isTop,
        Instant publishTime,
        Double latitude,
        Double longitude
) {
}
