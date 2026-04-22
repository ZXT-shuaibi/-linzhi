package com.zhiguang.be.search;

import java.util.List;

/**
 * 搜索帖子结果。
 */
public record SearchPostsData(
        List<SearchResultItem> items,
        CursorPageMeta page
) {
}
