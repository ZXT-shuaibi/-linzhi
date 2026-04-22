package com.zhiguang.be.trade.kafka;

import java.time.Instant;

/**
 * 交易下单事件。
 * 用于在高并发入口预扣成功后，把真正的落单动作异步化。
 */
public record TradeOrderEvent(
        String orderNo,
        long activityId,
        long buyerId,
        int quantity,
        Instant submittedAt
) {
}
