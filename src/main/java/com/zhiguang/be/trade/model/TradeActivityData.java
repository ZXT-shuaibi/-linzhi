package com.zhiguang.be.trade.model;

import com.fasterxml.jackson.annotation.JsonFormat;

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
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal originalPrice,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
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
