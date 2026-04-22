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
        Integer isTop,
        Instant publishTime,
        Double latitude,
        Double longitude
) {
}
