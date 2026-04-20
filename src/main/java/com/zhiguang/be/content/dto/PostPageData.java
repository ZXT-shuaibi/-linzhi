package com.zhiguang.be.content.dto;

import java.util.List;

/**
 * 内容分页结果。
 */
public record PostPageData(
        List<PostCard> items,
        int page,
        int size,
        boolean hasMore
) {
}
