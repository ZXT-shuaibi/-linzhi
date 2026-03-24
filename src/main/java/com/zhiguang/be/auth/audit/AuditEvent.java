package com.zhiguang.be.auth.audit;

import java.time.Instant;

/**
 * 审计事件记录。
 */
public record AuditEvent(
        String eventType,
        String identifier,
        boolean success,
        String message,
        Instant timestamp
) {
    public static AuditEvent of(String eventType, String identifier, boolean success, String message) {
        return new AuditEvent(eventType, identifier, success, message, Instant.now());
    }
}
