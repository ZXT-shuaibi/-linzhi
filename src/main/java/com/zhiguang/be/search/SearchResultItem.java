package com.zhiguang.be.search;

import java.util.List;

/**
 * 搜索结果项。
 */
public record SearchResultItem(
        String postId,
        String title,
        String summary,
        Double score,
        Double distanceMeters,
        List<String> searchAfter
) {
}
