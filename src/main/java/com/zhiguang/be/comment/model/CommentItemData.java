package com.zhiguang.be.comment.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record CommentItemData(
        @JsonProperty("id") String commentId,
        String postId,
        String content,
        @JsonProperty("authorId") String authorUserId,
        @JsonProperty("authorNickname") String authorNickname,
        @JsonProperty("authorAvatar") String authorAvatar,
        Instant createdAt
) {
}
