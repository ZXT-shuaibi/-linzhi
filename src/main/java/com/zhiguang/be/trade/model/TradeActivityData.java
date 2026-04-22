package com.zhiguang.be.trade.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 交易活动返回数据。
 */
public record TradeActivityData(
        String activityId,
        String title,
        String description,
        String cover,
        BigDecimal originalPrice,
        BigDecimal seckillPrice,
        int totalStock,
        int availableStock,
        int perUserLimit,
        String status,
        Instant beginTime,
        Instant endTime,
        int payTimeoutMinutes,
        boolean active
) {
}
