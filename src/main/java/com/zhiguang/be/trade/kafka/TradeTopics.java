package com.zhiguang.be.trade.kafka;

/**
 * 交易模块 Kafka 主题定义。
 */
public final class TradeTopics {

    /**
     * 下单事件主题。
     */
    public static final String ORDER_EVENTS = "trade.order.events";

    private TradeTopics() {
    }
}
