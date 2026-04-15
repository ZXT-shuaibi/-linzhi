package com.zhiguang.be.content.dto;

import java.time.Instant;

/**
 * 草稿创建结果。
 */
public record DraftData(
        String postId,
        String status,
        Instant createdAt
) {
}
