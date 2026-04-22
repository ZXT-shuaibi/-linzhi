package com.zhiguang.be.trade.model;

import java.time.Instant;

/**
 * 订单受理状态数据。
 * 用于异步下单场景下的状态轮询。
 */
public record TradeOrderStatusData(
        String orderNo,
        String status,
        String message,
        Instant updatedAt
) {
}
