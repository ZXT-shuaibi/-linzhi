package com.zhiguang.be.search;

import java.time.Instant;

/**
 * 搜索索引文档原始行。
 */
public record SearchIndexDocumentRow(
        Long postId,
        String title,
        String summary,
        String tagsJson,
        Integer isTop,
        Instant publishTime,
        Double latitude,
        Double longitude,
        String status,
        String visible
) {
}
