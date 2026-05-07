package com.zhiguang.be.comment.model;

import java.util.List;

public record CommentPageData(
        List<CommentItemData> items,
        int page,
        int size,
        boolean hasMore
) {
}
