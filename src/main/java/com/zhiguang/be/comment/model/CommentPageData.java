package com.zhiguang.be.comment.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CommentPageData(
        List<CommentItemData> items,
        @JsonProperty("page") CommentPageMeta pageMeta,
        boolean hasMore
) {
    public record CommentPageMeta(int page, int size, int total) {}
}
