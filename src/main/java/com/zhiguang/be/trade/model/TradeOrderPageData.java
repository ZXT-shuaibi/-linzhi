package com.zhiguang.be.trade.model;

import java.util.List;

/**
 * 我的订单分页数据。
 */
public record TradeOrderPageData(
        List<TradeOrderData> items,
        int page,
        int size,
        long total,
        boolean hasMore
) {
}
