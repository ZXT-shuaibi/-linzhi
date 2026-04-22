package com.zhiguang.be.trade.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单详情数据。
 */
public record TradeOrderData(
        String orderNo,
        String activityId,
        String activityTitle,
        String activityCover,
        BigDecimal amount,
        int quantity,
        String status,
        String payChannel,
        Instant orderTime,
        Instant expireAt,
        Instant payTime,
        Instant closeTime,
        String closeReason
) {
}
