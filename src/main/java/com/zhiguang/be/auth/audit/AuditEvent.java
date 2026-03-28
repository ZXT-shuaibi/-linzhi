package com.zhiguang.be.auth.audit;

import java.time.Instant;

/**
 * 审计事件数据结构。
 * 用于描述一次认证相关操作的类型、主体、结果和时间信息。
 */
public record AuditEvent(
        String eventType,
        String identifier,
        boolean success,
        String message,
        Instant timestamp
) {
    /**
     * 创建带当前时间戳的审计事件。
     * 便于调用方只关注事件内容，而不必手动传递时间。
     *
     * @param eventType 事件类型
     * @param identifier 操作主体标识
     * @param success 是否成功
     * @param message 事件说明
     * @return 审计事件对象
     */
    public static AuditEvent of(String eventType, String identifier, boolean success, String message) {
        return new AuditEvent(eventType, identifier, success, message, Instant.now());
    }
}