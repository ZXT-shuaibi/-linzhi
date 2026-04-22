package com.zhiguang.be.trade.model;

import java.time.Instant;

/**
 * 下单受理结果。
 */
public record TradeSubmitData(
        String orderNo,
        String status,
        Instant submittedAt,
        String message
) {
}
