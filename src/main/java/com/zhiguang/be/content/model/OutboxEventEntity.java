package com.zhiguang.be.content.model;

import java.time.Instant;

/**
 * outbox 事件实体。
 */
public record OutboxEventEntity(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        String status,
        int retryCount,
        Instant createdAt
) {
}
