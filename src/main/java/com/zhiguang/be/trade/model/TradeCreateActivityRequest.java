package com.zhiguang.be.trade.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 创建交易活动请求。
 */
public record TradeCreateActivityRequest(
        @NotBlank(message = "活动标题不能为空")
        String title,
        String description,
        String cover,
        @NotNull(message = "原价不能为空")
        @DecimalMin(value = "0.01", message = "原价必须大于 0")
        BigDecimal originalPrice,
        @NotNull(message = "活动价不能为空")
        @DecimalMin(value = "0.01", message = "活动价必须大于 0")
        BigDecimal seckillPrice,
        @Min(value = 1, message = "库存必须大于 0")
        int totalStock,
        @Min(value = 1, message = "限购数必须大于 0")
        int perUserLimit,
        @NotNull(message = "开始时间不能为空")
        Instant beginTime,
        @NotNull(message = "结束时间不能为空")
        Instant endTime,
        @Min(value = 1, message = "支付超时时间必须大于 0")
        int payTimeoutMinutes
) {
}
