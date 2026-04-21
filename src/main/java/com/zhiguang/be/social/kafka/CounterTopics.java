package com.zhiguang.be.social.kafka;

/**
 * 社交计数相关 Kafka 主题常量。
 */
public final class CounterTopics {

    /**
     * 点赞、收藏等计数事件主题。
     */
    public static final String EVENTS = "counter-events";

    private CounterTopics() {
    }
}
