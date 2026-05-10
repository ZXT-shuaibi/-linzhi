package com.zhiguang.be.comment.model;

import java.time.Instant;

public record CommentItemData(
        String commentId,
        String postId,
        String content,
        CommentAuthorData author,
        Instant createdAt
) {
}
