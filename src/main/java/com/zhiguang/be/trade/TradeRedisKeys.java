package com.zhiguang.be.trade;

/**
 * 交易模块 Redis 键规则。
 * 统一管理活动缓存、库存、用户购买计数和锁键。
 */
public final class TradeRedisKeys {

    /**
     * 空值占位符。
     */
    public static final String NULL_MARKER = "__NULL__";

    private TradeRedisKeys() {
    }

    /**
     * 活动详情缓存键。
     */
    public static String activityCacheKey(long activityId) {
        return "trade:activity:" + activityId;
    }

    /**
     * 活动热点重建锁键。
     */
    public static String activityLockKey(long activityId) {
        return "trade:activity:lock:" + activityId;
    }

    /**
     * 活动库存热点键。
     */
    public static String activityStockKey(long activityId) {
        return "trade:activity:stock:" + activityId;
    }

    /**
     * 活动用户购买计数键。
     */
    public static String activityBuyerCounterKey(long activityId) {
        return "trade:activity:buyer:" + activityId;
    }

    /**
     * 用户提交订单锁键。
     */
    public static String submitLockKey(long activityId, long userId) {
        return "trade:submit:lock:" + activityId + ":" + userId;
    }

    /**
     * 订单异步受理状态键。
     */
    public static String orderStatusKey(long userId, String orderNo) {
        return "trade:order:status:" + userId + ":" + orderNo;
    }

    /**
     * 异步下单受理阶段的取消标记键。
     */
    public static String orderCancelMarkerKey(long userId, String orderNo) {
        return "trade:order:cancel:" + userId + ":" + orderNo;
    }

    /**
     * 订单异步落库过程锁。
     */
    public static String orderProcessLockKey(long userId, String orderNo) {
        return "trade:order:process:" + userId + ":" + orderNo;
    }
}
