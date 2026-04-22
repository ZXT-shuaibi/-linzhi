package com.zhiguang.be.trade.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 模拟支付请求。
 */
public record TradePayRequest(
        @NotBlank(message = "支付渠道不能为空")
        String payChannel
) {
}
