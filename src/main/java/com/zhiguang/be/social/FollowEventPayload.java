package com.zhiguang.be.social;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 关注事件载荷。
 */
public class FollowEventPayload {

    private final String eventId;
    private final String eventType;
    private final String followerId;
    private final String followeeId;
    private final String relationId;
    private final Instant occurredAt;

    /**
     * 构造关注事件载荷。
     *
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param followerId 关注者 ID
     * @param followeeId 被关注者 ID
     * @param relationId following 主表关系 ID
     * @param occurredAt 事件发生时间
     */
    @JsonCreator
    public FollowEventPayload(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("followerId") String followerId,
            @JsonProperty("followeeId") String followeeId,
            @JsonProperty("relationId") String relationId,
            @JsonProperty("occurredAt") Instant occurredAt
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.followerId = followerId;
        this.followeeId = followeeId;
        this.relationId = relationId;
        this.occurredAt = occurredAt;
    }

    /**
     * 构造关注事件对象。
     *
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param followerId 关注者 ID
     * @param followeeId 被关注者 ID
     * @return 关注事件载荷
     */
    public static FollowEventPayload of(long eventId, String eventType, long followerId, long followeeId) {
        return of(eventId, eventType, followerId, followeeId, 0L);
    }

    /**
     * 构造关注事件对象。
     *
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param followerId 关注者 ID
     * @param followeeId 被关注者 ID
     * @param relationId following 主表关系 ID
     * @return 关注事件载荷
     */
    public static FollowEventPayload of(long eventId, String eventType, long followerId, long followeeId, long relationId) {
        return new FollowEventPayload(
                String.valueOf(eventId),
                eventType,
                String.valueOf(followerId),
                String.valueOf(followeeId),
                relationId <= 0L ? null : String.valueOf(relationId),
                Instant.now()
        );
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getFollowerId() {
        return followerId;
    }

    public String getFolloweeId() {
        return followeeId;
    }

    public String getRelationId() {
        return relationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
