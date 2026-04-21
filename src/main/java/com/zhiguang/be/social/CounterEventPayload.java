package com.zhiguang.be.social;

import java.time.Instant;

/**
 * 互动计数事件载荷。
 */
public class CounterEventPayload {

    private final String eventId;
    private final String eventType;
    private final String targetType;
    private final String targetId;
    private final String action;
    private final String operatorId;
    private final int delta;
    private final Instant occurredAt;

    /**
     * 构造互动计数事件载荷。
     *
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @param operatorId 操作人 ID
     * @param delta 计数变化量，激活动作为 1，取消动作为 -1
     * @param occurredAt 事件发生时间
     */
    public CounterEventPayload(
            String eventId,
            String eventType,
            String targetType,
            String targetId,
            String action,
            String operatorId,
            int delta,
            Instant occurredAt
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.action = action;
        this.operatorId = operatorId;
        this.delta = delta;
        this.occurredAt = occurredAt;
    }

    /**
     * 构造一条互动动作事件载荷。
     *
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作类型
     * @param operatorId 操作人 ID
     * @param delta 计数变化量，激活动作为 1，取消动作为 -1
     * @return 互动事件载荷
     */
    public static CounterEventPayload of(
            long eventId,
            String eventType,
            String targetType,
            long targetId,
            String action,
            long operatorId,
            int delta
    ) {
        return new CounterEventPayload(
                String.valueOf(eventId),
                eventType,
                targetType,
                String.valueOf(targetId),
                action,
                String.valueOf(operatorId),
                delta,
                Instant.now()
        );
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getAction() {
        return action;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public int getDelta() {
        return delta;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
