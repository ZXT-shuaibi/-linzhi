package com.zhiguang.be.trade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 交易模块数据访问接口。
 * 负责活动、订单和交易补偿事件的数据库操作。
 */
@Mapper
public interface TradeMapper {

    /**
     * 查询活动详情。
     */
    Map<String, Object> findActivityById(@Param("activityId") long activityId);

    /**
     * 查询公开活动列表。
     */
    List<Map<String, Object>> listPublicActivities(@Param("now") Instant now, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 统计公开活动总数。
     */
    long countPublicActivities(@Param("now") Instant now);

    /**
     * 新建活动。
     */
    int insertActivity(
            @Param("id") long id,
            @Param("title") String title,
            @Param("description") String description,
            @Param("cover") String cover,
            @Param("originalPrice") BigDecimal originalPrice,
            @Param("seckillPrice") BigDecimal seckillPrice,
            @Param("totalStock") int totalStock,
            @Param("perUserLimit") int perUserLimit,
            @Param("beginTime") Instant beginTime,
            @Param("endTime") Instant endTime,
            @Param("payTimeoutMinutes") int payTimeoutMinutes
    );

    /**
     * 查询订单详情。
     */
    Map<String, Object> findOrderByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 查询当前用户订单详情。
     */
    Map<String, Object> findOrderByOrderNoAndBuyer(@Param("orderNo") String orderNo, @Param("buyerId") long buyerId);

    /**
     * 查询用户订单列表。
     */
    List<Map<String, Object>> listOrdersByBuyer(@Param("buyerId") long buyerId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 统计用户订单数。
     */
    long countOrdersByBuyer(@Param("buyerId") long buyerId);

    /**
     * 查询用户已存在的活跃订单数量。
     */
    Integer sumActiveOrderQuantity(@Param("activityId") long activityId, @Param("buyerId") long buyerId);

    /**
     * 扣减数据库库存。
     */
    int decreaseActivityStock(@Param("activityId") long activityId, @Param("quantity") int quantity, @Param("version") long version);

    /**
     * 回补数据库库存。
     */
    int increaseActivityStock(@Param("activityId") long activityId, @Param("quantity") int quantity, @Param("version") long version);

    /**
     * 创建订单。
     */
    int insertOrder(
            @Param("id") long id,
            @Param("orderNo") String orderNo,
            @Param("activityId") long activityId,
            @Param("buyerId") long buyerId,
            @Param("quantity") int quantity,
            @Param("amount") BigDecimal amount,
            @Param("status") String status,
            @Param("orderTime") Instant orderTime,
            @Param("expireAt") Instant expireAt
    );

    /**
     * 支付订单。
     */
    int markOrderPaid(
            @Param("orderNo") String orderNo,
            @Param("buyerId") long buyerId,
            @Param("payChannel") String payChannel,
            @Param("now") Instant now
    );

    /**
     * 主动取消未支付订单。
     */
    int cancelPendingOrder(
            @Param("orderNo") String orderNo,
            @Param("buyerId") long buyerId,
            @Param("now") Instant now,
            @Param("closeReason") String closeReason
    );

    /**
     * 关闭超时订单。
     */
    int closeExpiredOrder(
            @Param("orderId") long orderId,
            @Param("now") Instant now,
            @Param("closeReason") String closeReason
    );

    /**
     * 查询全部超时未支付订单。
     */
    List<Map<String, Object>> listExpiredPendingOrders(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * 查询指定用户的超时未支付订单。
     */
    List<Map<String, Object>> listExpiredPendingOrdersByBuyer(
            @Param("buyerId") long buyerId,
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * 写入交易模块补偿事件。
     */
    int insertOutboxEvent(
            @Param("id") long id,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") long aggregateId,
            @Param("eventType") String eventType,
            @Param("payload") String payload
    );

    /**
     * 查询待处理的交易补偿事件。
     */
    List<Map<String, Object>> listPendingTradeOutbox(@Param("limit") int limit);

    /**
     * 标记补偿事件处理成功。
     */
    int markOutboxPublished(@Param("id") long id);

    /**
     * 标记补偿事件处理失败。
     */
    int markOutboxFailed(@Param("id") long id, @Param("lastError") String lastError);
}
