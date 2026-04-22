package com.zhiguang.be.search;

import java.util.List;

/**
 * 游标分页元信息。
 */
public record CursorPageMeta(
        int page,
        int size,
        boolean hasMore,
        String nextAfter,
        List<String> searchAfter
) {
}
