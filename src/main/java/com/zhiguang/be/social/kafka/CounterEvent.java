package com.zhiguang.be.social.kafka;

/**
 * 计数事件模型。
 * 用于描述一次点赞或收藏状态变化对应的计数增量，
 * 既可用于 Kafka 发送，也可用于灾难回放时反序列化历史事件。
 */
public class CounterEvent {

    private String entityType;
    private String entityId;
    private String metric;
    private int idx;
    private long userId;
    private int delta;

    /**
     * Jackson 反序列化需要的无参构造方法。
     */
    public CounterEvent() {
    }

    /**
     * 构造计数事件。
     *
     * @param entityType 实体类型
     * @param entityId 实体 ID
     * @param metric 指标名称
     * @param idx 指标槽位下标
     * @param userId 用户 ID
     * @param delta 增量值
     */
    public CounterEvent(String entityType, String entityId, String metric, int idx, long userId, int delta) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.metric = metric;
        this.idx = idx;
        this.userId = userId;
        this.delta = delta;
    }

    /**
     * 快速构造计数事件。
     *
     * @param entityType 实体类型
     * @param entityId 实体 ID
     * @param metric 指标名称
     * @param idx 指标槽位下标
     * @param userId 用户 ID
     * @param delta 增量值
     * @return 计数事件
     */
    public static CounterEvent of(String entityType, String entityId, String metric, int idx, long userId, int delta) {
        return new CounterEvent(entityType, entityId, metric, idx, userId, delta);
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public int getIdx() {
        return idx;
    }

    public void setIdx(int idx) {
        this.idx = idx;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public int getDelta() {
        return delta;
    }

    public void setDelta(int delta) {
        this.delta = delta;
    }
}
