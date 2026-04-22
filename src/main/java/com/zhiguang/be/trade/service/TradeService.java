package com.zhiguang.be.trade.service;

import com.zhiguang.be.trade.model.TradeActivityData;
import com.zhiguang.be.trade.model.TradeActivityListData;
import com.zhiguang.be.trade.model.TradeCreateActivityRequest;
import com.zhiguang.be.trade.model.TradeOrderData;
import com.zhiguang.be.trade.model.TradeOrderPageData;
import com.zhiguang.be.trade.model.TradePayRequest;
import com.zhiguang.be.trade.model.TradeOrderStatusData;
import com.zhiguang.be.trade.model.TradeSubmitData;

/**
 * 交易模块服务接口。
 * 对外提供活动查询、活动创建、下单、支付和我的订单能力。
 */
public interface TradeService {

    /**
     * 查询活动列表。
     */
    TradeActivityListData listActivities(int page, int size);

    /**
     * 查询活动详情。
     */
    TradeActivityData getActivity(long activityId);

    /**
     * 创建活动。
     */
    TradeActivityData createActivity(long currentUserId, TradeCreateActivityRequest request);

    /**
     * 提交订单。
     */
    TradeSubmitData placeOrder(long currentUserId, long activityId);

    /**
     * 支付订单。
     */
    TradeOrderData pay(long currentUserId, String orderNo, TradePayRequest request);

    /**
     * 查询我的订单受理状态。
     */
    TradeOrderStatusData getMyOrderStatus(long currentUserId, String orderNo);

    /**
     * 查询我的订单列表。
     */
    TradeOrderPageData listMyOrders(long currentUserId, int page, int size);

    /**
     * 查询我的订单详情。
     */
    TradeOrderData getMyOrder(long currentUserId, String orderNo);
}
