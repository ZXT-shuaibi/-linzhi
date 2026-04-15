package com.zhiguang.be.content.model;

import java.time.Instant;

/**
 * 内容到 discover 的同步事件载荷。
 */
public record PostSyncPayload(
        String eventId,
        String eventType,
        String postId,
        Instant occurredAt
) {
}
