package com.zhiguang.be.trade.model;

import java.util.List;

/**
 * 交易活动分页数据。
 */
public record TradeActivityListData(
        List<TradeActivityData> items,
        int page,
        int size,
        long total,
        boolean hasMore
) {
}
