package com.zhiguang.be.trade.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.CacheRegions;
import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.trade.TradeRedisKeys;
import com.zhiguang.be.trade.kafka.TradeOrderEvent;
import com.zhiguang.be.trade.kafka.TradeOrderProducer;
import com.zhiguang.be.trade.mapper.TradeMapper;
import com.zhiguang.be.trade.model.TradeActivityData;
import com.zhiguang.be.trade.model.TradeActivityListData;
import com.zhiguang.be.trade.model.TradeCreateActivityRequest;
import com.zhiguang.be.trade.model.TradeOrderData;
import com.zhiguang.be.trade.model.TradeOrderPageData;
import com.zhiguang.be.trade.model.TradePayRequest;
import com.zhiguang.be.trade.model.TradeOrderStatusData;
import com.zhiguang.be.trade.model.TradeSubmitData;
import com.zhiguang.be.trade.service.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 交易模块服务实现。
 * 负责活动缓存、Lua 预扣、异步落单、支付、主动/被动关单和 Redis/MySQL 一致性补偿。
 */
@Service
public class TradeServiceImpl implements TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeServiceImpl.class);
    private static final String ORDER_STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String ORDER_STATUS_PAID = "PAID";
    private static final String ORDER_STATUS_CLOSED = "CLOSED";
    private static final Duration ACTIVITY_LOCAL_CACHE_TTL = Duration.ofSeconds(5);

    private final TradeMapper tradeMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheService cacheService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final TradeOrderProducer tradeOrderProducer;
    private final Executor tradeOrderExecutor;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DefaultRedisScript<Long> preDeductScript;
    private final DefaultRedisScript<Long> safeUnlockScript;
    private final DefaultRedisScript<Long> resetStockAndReleaseUserScript;

    @Value("${trade.order.pay-timeout-minutes:15}")
    private int defaultPayTimeoutMinutes;
    @Value("${trade.order.close-batch-size:100}")
    private int closeBatchSize;
    @Value("${trade.order.status-ttl-hours:48}")
    private int orderStatusTtlHours;
    @Value("${trade.cache.activity-ttl-seconds:300}")
    private long activityTtlSeconds;
    @Value("${trade.cache.null-ttl-seconds:60}")
    private long activityNullTtlSeconds;
    @Value("${trade.cache.rebuild-lock-seconds:5}")
    private long rebuildLockSeconds;
    @Value("${trade.cache.jitter-seconds:120}")
    private long jitterSeconds;

    /**
     * 注入交易模块依赖。
     */
    public TradeServiceImpl(
            TradeMapper tradeMapper,
            StringRedisTemplate stringRedisTemplate,
            CacheService cacheService,
            SnowflakeIdGenerator snowflakeIdGenerator,
            TradeOrderProducer tradeOrderProducer,
            @Qualifier("tradeOrderExecutor") Executor tradeOrderExecutor,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.tradeMapper = tradeMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheService = cacheService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.tradeOrderProducer = tradeOrderProducer;
        this.tradeOrderExecutor = tradeOrderExecutor;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);

        this.preDeductScript = new DefaultRedisScript<Long>();
        this.preDeductScript.setResultType(Long.class);
        this.preDeductScript.setScriptText(
                "local stockKey = KEYS[1]\n"
                        + "local buyerKey = KEYS[2]\n"
                        + "local userId = ARGV[1]\n"
                        + "local quantity = tonumber(ARGV[2])\n"
                        + "local perUserLimit = tonumber(ARGV[3])\n"
                        + "local ttl = tonumber(ARGV[4])\n"
                        + "local stock = tonumber(redis.call('GET', stockKey) or '-1')\n"
                        + "if stock < 0 then return -3 end\n"
                        + "if stock < quantity then return -1 end\n"
                        + "local bought = tonumber(redis.call('HGET', buyerKey, userId) or '0')\n"
                        + "if bought + quantity > perUserLimit then return -2 end\n"
                        + "redis.call('DECRBY', stockKey, quantity)\n"
                        + "redis.call('HINCRBY', buyerKey, userId, quantity)\n"
                        + "if ttl > 0 then redis.call('EXPIRE', buyerKey, ttl) end\n"
                        + "return 1\n"
        );

        this.safeUnlockScript = new DefaultRedisScript<Long>();
        this.safeUnlockScript.setResultType(Long.class);
        this.safeUnlockScript.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
                        + "  return redis.call('DEL', KEYS[1])\n"
                        + "end\n"
                        + "return 0\n"
        );

        this.resetStockAndReleaseUserScript = new DefaultRedisScript<Long>();
        this.resetStockAndReleaseUserScript.setResultType(Long.class);
        this.resetStockAndReleaseUserScript.setScriptText(
                "local stockKey = KEYS[1]\n"
                        + "local buyerKey = KEYS[2]\n"
                        + "local currentStock = tonumber(ARGV[1])\n"
                        + "local userId = ARGV[2]\n"
                        + "local quantity = tonumber(ARGV[3])\n"
                        + "local ttl = tonumber(ARGV[4])\n"
                        + "redis.call('SET', stockKey, currentStock)\n"
                        + "if ttl > 0 then redis.call('EXPIRE', stockKey, ttl) end\n"
                        + "local remaining = tonumber(redis.call('HINCRBY', buyerKey, userId, -quantity))\n"
                        + "if remaining <= 0 then redis.call('HDEL', buyerKey, userId) end\n"
                        + "if redis.call('HLEN', buyerKey) == 0 then redis.call('DEL', buyerKey) end\n"
                        + "return 1\n"
        );
    }

    /**
     * 查询活动列表。
     */
    @Override
    public TradeActivityListData listActivities(String stage, int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = normalizeSize(size, 20);
        int offset = (normalizedPage - 1) * normalizedSize;
        Instant now = Instant.now();
        String normalizedStage = normalizeActivityStage(stage);
        List<Map<String, Object>> rows = tradeMapper.listPublicActivities(now, normalizedStage, normalizedSize, offset);
        long total = tradeMapper.countPublicActivities(now, normalizedStage);
        List<TradeActivityData> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(toActivityData(row));
        }
        return new TradeActivityListData(items, normalizedPage, normalizedSize, total, offset + items.size() < total);
    }

    /**
     * 查询活动详情。
     */
    @Override
    public TradeActivityData getActivity(long activityId) {
        Map<String, Object> snapshot = loadActivitySnapshot(activityId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.TRADE_ACTIVITY_NOT_FOUND, HttpStatus.NOT_FOUND, "活动不存在");
        }
        return toActivityData(snapshot);
    }

    /**
     * 创建活动。
     */
    @Override
    public TradeActivityData createActivity(long currentUserId, TradeCreateActivityRequest request) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        validateCreateRequest(request);
        long activityId = snowflakeIdGenerator.nextId();
        tradeMapper.insertActivity(
                activityId,
                request.title().trim(),
                normalizeNullableText(request.description()),
                normalizeNullableText(request.cover()),
                request.originalPrice().setScale(2, RoundingMode.HALF_UP),
                request.seckillPrice().setScale(2, RoundingMode.HALF_UP),
                request.totalStock(),
                request.perUserLimit(),
                request.beginTime(),
                request.endTime(),
                request.payTimeoutMinutes() > 0 ? request.payTimeoutMinutes() : defaultPayTimeoutMinutes
        );
        Map<String, Object> activity = requireActivity(activityId);
        cacheActivitySnapshot(activity);
        return toActivityData(activity);
    }

    /**
     * 提交订单。
     */
    @Override
    public TradeSubmitData placeOrder(long currentUserId, long activityId, int quantity) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }

        int normalizedQuantity = normalizeOrderQuantity(quantity);

        String lockKey = TradeRedisKeys.submitLockKey(activityId, currentUserId);
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofSeconds(3));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "下单处理中，请勿重复提交");
        }

        try {
            Map<String, Object> activity = requireActivity(activityId);
            Instant now = Instant.now();
            validateActivityForOrdering(activity, now);
            ensureStockHotKey(activity);

            Long precheck = stringRedisTemplate.execute(
                    preDeductScript,
                    List.of(TradeRedisKeys.activityStockKey(activityId), TradeRedisKeys.activityBuyerCounterKey(activityId)),
                    String.valueOf(currentUserId),
                    String.valueOf(normalizedQuantity),
                    String.valueOf(asInt(activity.get("perUserLimit"))),
                    String.valueOf(computeBuyerCounterTtlSeconds(asInstant(activity.get("endTime"))))
            );
            handlePrecheckResult(precheck, normalizedQuantity);

            TradeOrderEvent event = new TradeOrderEvent(
                    String.valueOf(snowflakeIdGenerator.nextId()),
                    activityId,
                    currentUserId,
                    normalizedQuantity,
                    now
            );

            markOrderStatus(currentUserId, event.orderNo(), "ACCEPTED", "下单请求已受理");

            boolean published = tradeOrderProducer.publish(event);
            if (!published) {
                submitLocally(event);
            }

            return new TradeSubmitData(event.orderNo(), "PENDING_CREATE", now, "下单请求已受理");
        } finally {
            releaseLock(lockKey, lockToken);
        }
    }

    /**
     * 模拟支付订单。
     */
    @Override
    public TradeOrderData pay(long currentUserId, String orderNo, TradePayRequest request) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }

        closeExpiredOrdersForBuyer(currentUserId);
        Instant now = Instant.now();
        int updated = tradeMapper.markOrderPaid(orderNo, currentUserId, request.payChannel().trim(), now);
        Map<String, Object> order = requireBuyerOrder(currentUserId, orderNo);
        if (updated != 1) {
            String status = asText(order.get("status"));
            Instant expireAt = asInstant(order.get("expireAt"));
            if (ORDER_STATUS_PAID.equals(status)) {
                return toOrderData(order);
            }
            if (ORDER_STATUS_PENDING_PAYMENT.equals(status) && expireAt != null && !now.isBefore(expireAt)) {
                closeExpiredOrdersForBuyer(currentUserId);
                order = requireBuyerOrder(currentUserId, orderNo);
                status = asText(order.get("status"));
            }
            if (ORDER_STATUS_CLOSED.equals(status)) {
                throw new BusinessException(ErrorCode.TRADE_ORDER_CLOSED, HttpStatus.CONFLICT, "订单已关闭");
            }
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "当前订单无法支付");
        }
        markOrderStatus(currentUserId, orderNo, ORDER_STATUS_PAID, "订单支付成功");
        return toOrderData(requireBuyerOrder(currentUserId, orderNo));
    }

    /**
     * 主动取消当前用户自己的未支付订单。
     */
    @Override
    public TradeOrderData cancelMyOrder(long currentUserId, String orderNo) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        closeExpiredOrdersForBuyer(currentUserId);
        Map<String, Object> order = tradeMapper.findOrderByOrderNoAndBuyer(orderNo, currentUserId);
        if (order == null || order.isEmpty()) {
            TradeOrderStatusData pendingData = loadOrderStatus(currentUserId, orderNo);
            if (pendingData != null && "ACCEPTED".equals(pendingData.status())) {
                String processToken = tryAcquireOrderProcessLock(currentUserId, orderNo);
                if (processToken == null) {
                    throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "订单创建中，请稍后再试取消");
                }
                try {
                    Map<String, Object> latestOrder = tradeMapper.findOrderByOrderNoAndBuyer(orderNo, currentUserId);
                    if (latestOrder == null || latestOrder.isEmpty()) {
                        markOrderCancelBeforeCreate(currentUserId, orderNo);
                        markOrderStatus(currentUserId, orderNo, ORDER_STATUS_CLOSED, "订单已取消");
                        return new TradeOrderData(
                                orderNo,
                                null,
                                null,
                                null,
                                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                                0,
                                ORDER_STATUS_CLOSED,
                                null,
                                pendingData.updatedAt(),
                                null,
                                null,
                                pendingData.updatedAt(),
                                "USER_CANCEL"
                        );
                    }
                    order = latestOrder;
                } finally {
                    releaseLock(TradeRedisKeys.orderProcessLockKey(currentUserId, orderNo), processToken);
                }
            }
            if (order == null || order.isEmpty()) {
                throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND, HttpStatus.NOT_FOUND, "订单不存在");
            }
        }
        String status = asText(order.get("status"));
        if (ORDER_STATUS_PAID.equals(status)) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_PAID, HttpStatus.CONFLICT, "订单已支付");
        }
        if (ORDER_STATUS_CLOSED.equals(status)) {
            return toOrderData(order);
        }

        Instant now = Instant.now();
        Instant expireAt = asInstant(order.get("expireAt"));
        if (ORDER_STATUS_PENDING_PAYMENT.equals(status) && expireAt != null && !now.isBefore(expireAt)) {
            closeExpiredOrdersForBuyer(currentUserId);
            return toOrderData(requireBuyerOrder(currentUserId, orderNo));
        }

        long activityId = asLong(order.get("activityId"));
        int quantity = asInt(order.get("quantity"));
        Boolean cancelled = transactionTemplate.execute(transactionStatus -> {
            Instant cancelTime = Instant.now();
            int updated = tradeMapper.cancelPendingOrder(orderNo, currentUserId, cancelTime, "USER_CANCEL");
            if (updated != 1) {
                return Boolean.FALSE;
            }
            increaseActivityStockWithRetry(activityId, quantity);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        syncRedisStockFromDbAndReleaseUser(activityId, currentUserId, quantity);
                        markOrderStatus(currentUserId, orderNo, ORDER_STATUS_CLOSED, "订单已取消");
                    } catch (Exception ex) {
                        log.warn("sync redis after order cancel failed, orderNo={}", orderNo, ex);
                        enqueueRedisReconcileEvent(activityId, currentUserId, quantity, orderNo, "USER_CANCEL");
                        markOrderStatus(currentUserId, orderNo, ORDER_STATUS_CLOSED, "订单已取消");
                    }
                }
            });
            return Boolean.TRUE;
        });

        if (!Boolean.TRUE.equals(cancelled)) {
            Map<String, Object> latest = requireBuyerOrder(currentUserId, orderNo);
            String latestStatus = asText(latest.get("status"));
            if (ORDER_STATUS_PAID.equals(latestStatus)) {
                throw new BusinessException(ErrorCode.TRADE_ORDER_PAID, HttpStatus.CONFLICT, "订单已支付");
            }
            return toOrderData(latest);
        }
        return toOrderData(requireBuyerOrder(currentUserId, orderNo));
    }

    /**
     * 查询我的订单受理状态。
     */
    @Override
    public TradeOrderStatusData getMyOrderStatus(long currentUserId, String orderNo) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        closeExpiredOrdersForBuyer(currentUserId);
        Map<String, Object> order = tradeMapper.findOrderByOrderNoAndBuyer(orderNo, currentUserId);
        if (order != null && !order.isEmpty()) {
            TradeOrderStatusData data = new TradeOrderStatusData(
                    orderNo,
                    asText(order.get("status")),
                    statusMessage(asText(order.get("status"))),
                    resolveOrderUpdatedAt(order)
            );
            markOrderStatus(currentUserId, data.orderNo(), data.status(), data.message(), data.updatedAt());
            return data;
        }
        TradeOrderStatusData pendingData = loadOrderStatus(currentUserId, orderNo);
        if (pendingData != null) {
            return pendingData;
        }
        throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND, HttpStatus.NOT_FOUND, "订单不存在");
    }

    /**
     * 查询我的订单列表。
     */
    @Override
    public TradeOrderPageData listMyOrders(long currentUserId, String status, int page, int size) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        closeExpiredOrdersForBuyer(currentUserId);
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = normalizeSize(size, 10);
        int offset = (normalizedPage - 1) * normalizedSize;
        String normalizedStatus = normalizeOrderStatus(status);
        List<Map<String, Object>> rows = tradeMapper.listOrdersByBuyer(currentUserId, normalizedStatus, normalizedSize, offset);
        long total = tradeMapper.countOrdersByBuyer(currentUserId, normalizedStatus);
        List<TradeOrderData> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(toOrderData(row));
        }
        return new TradeOrderPageData(items, normalizedPage, normalizedSize, total, offset + items.size() < total);
    }

    /**
     * 查询我的订单详情。
     */
    @Override
    public TradeOrderData getMyOrder(long currentUserId, String orderNo) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        closeExpiredOrdersForBuyer(currentUserId);
        return toOrderData(requireBuyerOrder(currentUserId, orderNo));
    }

    /**
     * 接收异步下单事件。
     * Kafka 模式和本地线程池回退模式都会走这里。
     */
    public void acceptOrderEvent(TradeOrderEvent event) {
        String processToken = waitForOrderProcessLock(event.buyerId(), event.orderNo());
        if (processToken == null) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "订单处理繁忙，请稍后重试");
        }
        try {
            if (isOrderCancelledBeforeCreate(event.buyerId(), event.orderNo())) {
                reconcileRedisReservation(event, "USER_CANCEL_BEFORE_CREATE");
                markOrderStatus(event.buyerId(), event.orderNo(), ORDER_STATUS_CLOSED, "订单已取消");
                return;
            }
            transactionTemplate.executeWithoutResult(status -> createOrderInTransaction(event));
            markOrderStatus(event.buyerId(), event.orderNo(), ORDER_STATUS_PENDING_PAYMENT, "订单已创建，等待支付");
        } catch (BusinessException ex) {
            log.info("accept trade order event failed by business, orderNo={}, message={}", event.orderNo(), ex.getMessage());
            markOrderStatus(event.buyerId(), event.orderNo(), "FAILED", ex.getMessage());
            reconcileRedisReservation(event, ex.getMessage());
        } catch (Exception ex) {
            log.warn("accept trade order event failed, orderNo={}", event.orderNo(), ex);
            markOrderStatus(event.buyerId(), event.orderNo(), "FAILED", "下单失败，请稍后重试");
            reconcileRedisReservation(event, "create-order-failed");
        } finally {
            releaseLock(TradeRedisKeys.orderProcessLockKey(event.buyerId(), event.orderNo()), processToken);
        }
    }

    /**
     * 定时主动关闭超时未支付订单。
     */
    @Scheduled(fixedDelayString = "${trade.order.close-scan-delay-ms:60000}")
    public void closeExpiredOrders() {
        while (true) {
            List<Map<String, Object>> rows = tradeMapper.listExpiredPendingOrders(Instant.now(), closeBatchSize);
            if (rows.isEmpty()) {
                return;
            }
            for (Map<String, Object> row : rows) {
                closeOrderRow(row, "AUTO_TIMEOUT");
            }
            if (rows.size() < closeBatchSize) {
                return;
            }
        }
    }

    /**
     * 定时处理 Redis/MySQL 对账补偿事件。
     */
    @Scheduled(fixedDelayString = "${trade.order.redis-reconcile-delay-ms:10000}")
    public void reconcilePendingRedisEvents() {
        List<Map<String, Object>> rows = tradeMapper.listPendingTradeOutbox(closeBatchSize);
        for (Map<String, Object> row : rows) {
            long eventId = asLong(row.get("id"));
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = objectMapper.readValue(asText(row.get("payload")), LinkedHashMap.class);
                syncRedisStockFromDbAndReleaseUser(
                        asLong(payload.get("activityId")),
                        asLong(payload.get("buyerId")),
                        asInt(payload.get("quantity"))
                );
                tradeMapper.markOutboxPublished(eventId);
            } catch (Exception ex) {
                log.warn("reconcile trade redis event failed, eventId={}", eventId, ex);
                tradeMapper.markOutboxFailed(eventId, shorten(ex.getMessage(), 255));
            }
        }
    }

    /**
     * 真正的订单落库事务。
     */
    private void createOrderInTransaction(TradeOrderEvent event) {
        if (tradeMapper.findOrderByOrderNo(event.orderNo()) != null) {
            return;
        }

        Map<String, Object> activity = requireActivity(event.activityId());
        Integer activeQuantityValue = tradeMapper.sumActiveOrderQuantity(event.activityId(), event.buyerId());
        int activeQuantity = activeQuantityValue == null ? 0 : activeQuantityValue.intValue();
        if (activeQuantity + event.quantity() > asInt(activity.get("perUserLimit"))) {
            throw new BusinessException(ErrorCode.TRADE_DUPLICATE_ORDER, HttpStatus.CONFLICT, "当前活动已达限购上限");
        }
        BigDecimal amount = asBigDecimal(activity.get("seckillPrice"))
                .multiply(BigDecimal.valueOf(event.quantity()))
                .setScale(2, RoundingMode.HALF_UP);
        Instant orderTime = Instant.now();
        int payTimeoutMinutes = Math.max(asInt(activity.get("payTimeoutMinutes")), 1);
        Instant expireAt = orderTime.plus(Duration.ofMinutes(payTimeoutMinutes));

        decreaseActivityStockWithRetry(event.activityId(), event.quantity());
        try {
            tradeMapper.insertOrder(
                    snowflakeIdGenerator.nextId(),
                    event.orderNo(),
                    event.activityId(),
                    event.buyerId(),
                    event.quantity(),
                    amount,
                    ORDER_STATUS_PENDING_PAYMENT,
                    orderTime,
                    expireAt
            );
        } catch (DuplicateKeyException ex) {
            increaseActivityStockWithRetry(event.activityId(), event.quantity());
        } catch (RuntimeException ex) {
            increaseActivityStockWithRetry(event.activityId(), event.quantity());
            throw ex;
        }
    }

    /**
     * 被动关单：用户查看订单时，先把自己的超时订单收口。
     */
    private void closeExpiredOrdersForBuyer(long buyerId) {
        while (true) {
            List<Map<String, Object>> rows = tradeMapper.listExpiredPendingOrdersByBuyer(buyerId, Instant.now(), closeBatchSize);
            if (rows.isEmpty()) {
                return;
            }
            for (Map<String, Object> row : rows) {
                closeOrderRow(row, "PASSIVE_TIMEOUT");
            }
            if (rows.size() < closeBatchSize) {
                return;
            }
        }
    }

    /**
     * 关闭单条超时订单并回补库存。
     */
    private void closeOrderRow(Map<String, Object> row, String closeReason) {
        Boolean closed = transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            int updated = tradeMapper.closeExpiredOrder(asLong(row.get("orderId")), now, closeReason);
            if (updated != 1) {
                return Boolean.FALSE;
            }
            long activityId = asLong(row.get("activityId"));
            int quantity = asInt(row.get("quantity"));
            long buyerId = asLong(row.get("buyerId"));
            String orderNo = asText(row.get("orderNo"));
            increaseActivityStockWithRetry(activityId, quantity);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        syncRedisStockFromDbAndReleaseUser(activityId, buyerId, quantity);
                        markOrderStatus(buyerId, orderNo, ORDER_STATUS_CLOSED, "订单已超时关闭");
                    } catch (Exception ex) {
                        log.warn("sync redis after order close failed, orderNo={}", orderNo, ex);
                        enqueueRedisReconcileEvent(activityId, buyerId, quantity, orderNo, closeReason);
                        markOrderStatus(buyerId, orderNo, ORDER_STATUS_CLOSED, "订单已超时关闭");
                    }
                }
            });
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(closed)) {
            log.info("close expired trade order success, orderNo={}, reason={}", asText(row.get("orderNo")), closeReason);
        }
    }

    /**
     * 活动创建请求校验。
     */
    private void validateCreateRequest(TradeCreateActivityRequest request) {
        if (request.seckillPrice().compareTo(request.originalPrice()) >= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "活动价必须低于原价");
        }
        if (!request.endTime().isAfter(request.beginTime())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "结束时间必须晚于开始时间");
        }
    }

    /**
     * 活动下单前状态校验。
     */
    private void validateActivityForOrdering(Map<String, Object> activity, Instant now) {
        String status = asText(activity.get("status"));
        Instant beginTime = asInstant(activity.get("beginTime"));
        Instant endTime = asInstant(activity.get("endTime"));
        if (!"published".equalsIgnoreCase(status) || beginTime == null || endTime == null) {
            throw new BusinessException(ErrorCode.TRADE_ACTIVITY_NOT_ACTIVE, HttpStatus.CONFLICT, "活动当前不可下单");
        }
        if (now.isBefore(beginTime)) {
            throw new BusinessException(ErrorCode.TRADE_ACTIVITY_NOT_ACTIVE, HttpStatus.CONFLICT, "活动尚未开始");
        }
        if (!now.isBefore(endTime)) {
            throw new BusinessException(ErrorCode.TRADE_ACTIVITY_NOT_ACTIVE, HttpStatus.CONFLICT, "活动已结束");
        }
    }

    /**
     * 处理 Lua 预扣结果。
     */
    private void handlePrecheckResult(Long precheck, int quantity) {
        if (precheck == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "预扣库存失败");
        }
        if (precheck.longValue() == -1L) {
            throw new BusinessException(ErrorCode.TRADE_STOCK_EMPTY, HttpStatus.CONFLICT, "库存不足");
        }
        if (precheck.longValue() == -2L) {
            throw new BusinessException(ErrorCode.TRADE_DUPLICATE_ORDER, HttpStatus.CONFLICT, "当前活动已下单，请勿重复提交");
        }
        if (precheck.longValue() == -3L) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "活动库存缓存未准备好，请稍后再试");
        }
    }

    /**
     * 本地下单回退。
     */
    private void submitLocally(TradeOrderEvent event) {
        try {
            tradeOrderExecutor.execute(() -> acceptOrderEvent(event));
        } catch (Exception ex) {
            log.warn("trade executor rejected, fallback to direct create, orderNo={}", event.orderNo(), ex);
            acceptOrderEvent(event);
        }
    }

    /**
     * 订单创建失败后，根据数据库当前值恢复 Redis 库存并释放用户预占资格。
     */
    private void reconcileRedisReservation(TradeOrderEvent event, String reason) {
        try {
            syncRedisStockFromDbAndReleaseUser(event.activityId(), event.buyerId(), event.quantity());
        } catch (Exception ex) {
            log.warn("reconcile redis after trade failure failed, orderNo={}, reason={}", event.orderNo(), reason, ex);
            enqueueRedisReconcileEvent(event.activityId(), event.buyerId(), event.quantity(), event.orderNo(), reason);
        }
    }

    /**
     * 同步数据库库存到 Redis，并释放用户购买计数。
     */
    private void syncRedisStockFromDbAndReleaseUser(long activityId, long buyerId, int quantity) {
        Map<String, Object> activity = requireActivity(activityId);
        long ttlSeconds = computeStockTtlSeconds(asInstant(activity.get("endTime")));
        Long result = stringRedisTemplate.execute(
                resetStockAndReleaseUserScript,
                List.of(TradeRedisKeys.activityStockKey(activityId), TradeRedisKeys.activityBuyerCounterKey(activityId)),
                String.valueOf(asInt(activity.get("availableStock"))),
                String.valueOf(buyerId),
                String.valueOf(quantity),
                String.valueOf(ttlSeconds)
        );
        if (result == null || result.longValue() != 1L) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Redis 库存同步失败");
        }
    }

    /**
     * 失败时写入交易补偿事件。
     */
    private void enqueueRedisReconcileEvent(long activityId, long buyerId, int quantity, String orderNo, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activityId", activityId);
        payload.put("buyerId", buyerId);
        payload.put("quantity", quantity);
        payload.put("orderNo", orderNo);
        payload.put("reason", reason);
        try {
            tradeMapper.insertOutboxEvent(
                    snowflakeIdGenerator.nextId(),
                    "trade_order",
                    activityId,
                    "trade_redis_sync",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (JsonProcessingException ex) {
            log.warn("serialize trade reconcile payload failed, orderNo={}", orderNo, ex);
        }
    }

    /**
     * 带乐观锁重试地扣减数据库库存。
     */
    private void decreaseActivityStockWithRetry(long activityId, int quantity) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Map<String, Object> activity = requireActivity(activityId);
            int updated = tradeMapper.decreaseActivityStock(activityId, quantity, asLong(activity.get("version")));
            if (updated == 1) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.TRADE_STOCK_EMPTY, HttpStatus.CONFLICT, "库存扣减失败，请稍后重试");
    }

    /**
     * 带乐观锁重试地回补数据库库存。
     */
    private void increaseActivityStockWithRetry(long activityId, int quantity) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Map<String, Object> activity = requireActivity(activityId);
            int updated = tradeMapper.increaseActivityStock(activityId, quantity, asLong(activity.get("version")));
            if (updated == 1) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "库存回补失败");
    }

    /**
     * 读取活动缓存快照。
     * 使用空值缓存、互斥锁和随机 TTL 防止穿透、击穿和雪崩。
     */
    private Map<String, Object> loadActivitySnapshot(long activityId) {
        String cacheKey = TradeRedisKeys.activityCacheKey(activityId);
        Map<String, Object> localSnapshot = cacheService.getLocal(CacheRegions.TRADE_ACTIVITY, cacheKey, Map.class);
        if (localSnapshot != null && !localSnapshot.isEmpty()) {
            return localSnapshot;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            String cached = cacheService.getRedisString(cacheKey);
            if (TradeRedisKeys.NULL_MARKER.equals(cached)) {
                return null;
            }
            if (cached != null && !cached.isBlank()) {
                Map<String, Object> snapshot = deserializeActivitySnapshot(cached);
                if (snapshot != null) {
                    cacheService.putLocal(CacheRegions.TRADE_ACTIVITY, cacheKey, snapshot, ACTIVITY_LOCAL_CACHE_TTL);
                    return snapshot;
                }
                cacheService.deleteRedis(cacheKey);
            }

            String lockKey = TradeRedisKeys.activityLockKey(activityId);
            String lockToken = UUID.randomUUID().toString();
            Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofSeconds(rebuildLockSeconds));
            if (Boolean.TRUE.equals(locked)) {
                try {
                    Map<String, Object> activity = tradeMapper.findActivityById(activityId);
                    if (activity == null || activity.isEmpty()) {
                        cacheService.putRedisString(cacheKey, TradeRedisKeys.NULL_MARKER, Duration.ofSeconds(activityNullTtlSeconds));
                        return null;
                    }
                    cacheActivitySnapshot(activity);
                    return activity;
                } finally {
                    releaseLock(lockKey, lockToken);
                }
            }

            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return tradeMapper.findActivityById(activityId);
    }

    /**
     * 将活动快照写入 Redis。
     */
    private void cacheActivitySnapshot(Map<String, Object> activity) {
        long activityId = asLong(activity.get("activityId"));
        try {
            String cacheKey = TradeRedisKeys.activityCacheKey(activityId);
            cacheService.putRedisString(
                    cacheKey,
                    objectMapper.writeValueAsString(activity),
                    Duration.ofSeconds(activityTtlSeconds + randomJitter())
            );
            cacheService.putLocal(CacheRegions.TRADE_ACTIVITY, cacheKey, activity, ACTIVITY_LOCAL_CACHE_TTL);
        } catch (JsonProcessingException ex) {
            log.warn("cache trade activity failed, activityId={}", activityId, ex);
        }
        ensureStockHotKey(activity);
    }

    /**
     * 确保热点库存键存在。
     */
    private void ensureStockHotKey(Map<String, Object> activity) {
        long activityId = asLong(activity.get("activityId"));
        String stockKey = TradeRedisKeys.activityStockKey(activityId);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            return;
        }
        Map<String, Object> latest = tradeMapper.findActivityById(activityId);
        Map<String, Object> stockSource = latest != null && !latest.isEmpty() ? latest : activity;
        long ttlSeconds = computeStockTtlSeconds(asInstant(activity.get("endTime")));
        stringRedisTemplate.opsForValue().set(
                stockKey,
                String.valueOf(asInt(stockSource.get("availableStock"))),
                Duration.ofSeconds(ttlSeconds)
        );
    }

    /**
     * 将缓存字符串还原为活动快照。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeActivitySnapshot(String cached) {
        try {
            return objectMapper.readValue(cached, LinkedHashMap.class);
        } catch (Exception ex) {
            log.warn("deserialize trade activity snapshot failed", ex);
            return null;
        }
    }

    /**
     * 尝试获取订单异步处理锁，供取消前二次确认使用。
     */
    private String tryAcquireOrderProcessLock(long buyerId, String orderNo) {
        String lockKey = TradeRedisKeys.orderProcessLockKey(buyerId, orderNo);
        String token = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(5));
        return Boolean.TRUE.equals(locked) ? token : null;
    }

    /**
     * 轮询获取订单异步处理锁，避免消费线程和取消请求并发踩踏。
     */
    private String waitForOrderProcessLock(long buyerId, String orderNo) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String token = tryAcquireOrderProcessLock(buyerId, orderNo);
            if (token != null) {
                return token;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 安全释放 Redis 锁。
     */
    private void releaseLock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(safeUnlockScript, List.of(lockKey), token);
        } catch (Exception ex) {
            log.debug("release trade redis lock failed, key={}", lockKey, ex);
        }
    }

    /**
     * 读取数据库活动，不存在时抛异常。
     */
    private Map<String, Object> requireActivity(long activityId) {
        Map<String, Object> activity = tradeMapper.findActivityById(activityId);
        if (activity == null || activity.isEmpty()) {
            throw new BusinessException(ErrorCode.TRADE_ACTIVITY_NOT_FOUND, HttpStatus.NOT_FOUND, "活动不存在");
        }
        return activity;
    }

    /**
     * 读取当前用户订单，不存在时抛异常。
     */
    private Map<String, Object> requireBuyerOrder(long buyerId, String orderNo) {
        Map<String, Object> order = tradeMapper.findOrderByOrderNoAndBuyer(orderNo, buyerId);
        if (order == null || order.isEmpty()) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND, HttpStatus.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    /**
     * 映射活动详情。
     */
    private TradeActivityData toActivityData(Map<String, Object> row) {
        long activityId = asLong(row.get("activityId"));
        Instant beginTime = asInstant(row.get("beginTime"));
        Instant endTime = asInstant(row.get("endTime"));
        int fallbackStock = asInt(row.get("availableStock"));
        int currentStock = readCurrentStock(activityId, fallbackStock, endTime);
        Instant now = Instant.now();
        boolean active = "published".equalsIgnoreCase(asText(row.get("status")))
                && beginTime != null
                && endTime != null
                && !now.isBefore(beginTime)
                && now.isBefore(endTime);
        return new TradeActivityData(
                String.valueOf(activityId),
                asText(row.get("title")),
                asText(row.get("description")),
                asText(row.get("cover")),
                asBigDecimal(row.get("originalPrice")),
                asBigDecimal(row.get("seckillPrice")),
                asInt(row.get("totalStock")),
                currentStock,
                asInt(row.get("perUserLimit")),
                asText(row.get("status")),
                beginTime,
                endTime,
                asInt(row.get("payTimeoutMinutes")),
                active
        );
    }

    /**
     * 映射订单详情。
     */
    private TradeOrderData toOrderData(Map<String, Object> row) {
        return new TradeOrderData(
                asText(row.get("orderNo")),
                String.valueOf(asLong(row.get("activityId"))),
                asText(row.get("activityTitle")),
                asText(row.get("activityCover")),
                asBigDecimal(row.get("amount")),
                asInt(row.get("quantity")),
                asText(row.get("status")),
                asText(row.get("payChannel")),
                asInstant(row.get("orderTime")),
                asInstant(row.get("expireAt")),
                asInstant(row.get("payTime")),
                asInstant(row.get("closeTime")),
                asText(row.get("closeReason"))
        );
    }

    /**
     * 读取当前库存。
     * 明细读取优先走 Redis，避免热点活动详情一直回库。
     */
    private int readCurrentStock(long activityId, int fallbackStock, Instant endTime) {
        String stock = stringRedisTemplate.opsForValue().get(TradeRedisKeys.activityStockKey(activityId));
        if (stock == null || stock.isBlank()) {
            Map<String, Object> latest = tradeMapper.findActivityById(activityId);
            int dbStock = latest == null || latest.isEmpty() ? fallbackStock : asInt(latest.get("availableStock"));
            long ttlSeconds = computeStockTtlSeconds(endTime);
            stringRedisTemplate.opsForValue().set(
                    TradeRedisKeys.activityStockKey(activityId),
                    String.valueOf(dbStock),
                    Duration.ofSeconds(ttlSeconds)
            );
            return dbStock;
        }
        return Integer.parseInt(stock);
    }

    /**
     * 统一限制分页大小。
     */
    private int normalizeSize(int size, int defaultSize) {
        if (size <= 0) {
            return defaultSize;
        }
        return Math.min(size, 50);
    }

    /**
     * 缁熶竴鏍￠獙涓嬪崟鏁伴噺銆?
     */
    private int normalizeOrderQuantity(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "下单数量必须大于 0");
        }
        return quantity;
    }

    /**
     * 缁熶竴娓呮礂娲诲姩闃舵绛涢€夊€笺€?
     */
    private String normalizeActivityStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "active", "upcoming", "sold_out" -> normalized;
            default -> throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    HttpStatus.BAD_REQUEST,
                    "活动阶段只支持 active、upcoming、sold_out"
            );
        };
    }

    /**
     * 缁熶竴娓呮礂璁㈠崟鐘舵€佺瓫閫夊€笺€?
     */
    private String normalizeOrderStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case ORDER_STATUS_PENDING_PAYMENT, ORDER_STATUS_PAID, ORDER_STATUS_CLOSED -> normalized;
            default -> throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    HttpStatus.BAD_REQUEST,
                    "订单状态只支持 PENDING_PAYMENT、PAID、CLOSED"
            );
        };
    }

    /**
     * 统一归一化可空文本。
     */
    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 计算库存键 TTL。
     * 默认跟活动结束时间对齐，并额外留出一段缓冲，便于后续补偿与查单。
     */
    private long computeStockTtlSeconds(Instant endTime) {
        if (endTime == null) {
            return activityTtlSeconds + randomJitter();
        }
        long remaining = Duration.between(Instant.now(), endTime).getSeconds();
        return Math.max(3600L, remaining + 3600L);
    }

    /**
     * 计算用户购买计数 TTL。
     * 至少覆盖活动持续时间，避免活动未结束时限购计数提前丢失。
     */
    private long computeBuyerCounterTtlSeconds(Instant endTime) {
        if (endTime == null) {
            return 86400L;
        }
        long remaining = Duration.between(Instant.now(), endTime).getSeconds();
        return Math.max(86400L, remaining + 86400L);
    }

    /**
     * 生成随机抖动秒数。
     */
    private long randomJitter() {
        if (jitterSeconds <= 0) {
            return 0L;
        }
        return Math.abs(UUID.randomUUID().getMostSignificantBits()) % (jitterSeconds + 1);
    }

    /**
     * 将对象安全转成 long。
     */
    private long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * 将对象安全转成 int。
     */
    private int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /**
     * 将对象安全转成字符串。
     */
    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 将对象安全转成金额。
     */
    private BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(2, RoundingMode.HALF_UP);
        }
        if (value instanceof Number number) {
            return new BigDecimal(String.valueOf(number)).setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 将对象安全转成时间。
     */
    private Instant asInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    /**
     * 截断错误消息，避免写入 outbox 时过长。
     */
    private String shorten(String message, int maxLength) {
        if (message == null) {
            return null;
        }
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength);
    }

    /**
     * 写入订单异步状态。
     */
    private void markOrderStatus(long buyerId, String orderNo, String status, String message) {
        markOrderStatus(buyerId, orderNo, status, message, Instant.now());
    }

    /**
     * 写入订单异步状态。
     */
    private void markOrderStatus(long buyerId, String orderNo, String status, String message, Instant updatedAt) {
        try {
            TradeOrderStatusData data = new TradeOrderStatusData(orderNo, status, message, updatedAt);
            stringRedisTemplate.opsForValue().set(
                    TradeRedisKeys.orderStatusKey(buyerId, orderNo),
                    objectMapper.writeValueAsString(data),
                    Duration.ofHours(Math.max(orderStatusTtlHours, 1))
            );
        } catch (Exception ex) {
            log.warn("mark trade order status failed, orderNo={}, status={}", orderNo, status, ex);
        }
    }

    /**
     * 标记订单在真正落库前已经被用户主动取消。
     */
    private void markOrderCancelBeforeCreate(long buyerId, String orderNo) {
        stringRedisTemplate.opsForValue().set(
                TradeRedisKeys.orderCancelMarkerKey(buyerId, orderNo),
                "1",
                Duration.ofHours(Math.max(orderStatusTtlHours, 1))
        );
    }

    /**
     * 判断订单是否在异步受理阶段已经被取消。
     */
    private boolean isOrderCancelledBeforeCreate(long buyerId, String orderNo) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(TradeRedisKeys.orderCancelMarkerKey(buyerId, orderNo)));
    }

    /**
     * 读取订单异步状态。
     */
    private TradeOrderStatusData loadOrderStatus(long buyerId, String orderNo) {
        String raw = stringRedisTemplate.opsForValue().get(TradeRedisKeys.orderStatusKey(buyerId, orderNo));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, TradeOrderStatusData.class);
        } catch (Exception ex) {
            log.warn("deserialize trade order status failed, orderNo={}", orderNo, ex);
            return null;
        }
    }

    /**
     * 根据订单状态补默认文案。
     */
    private String statusMessage(String status) {
        if (ORDER_STATUS_PENDING_PAYMENT.equals(status)) {
            return "订单已创建，等待支付";
        }
        if (ORDER_STATUS_PAID.equals(status)) {
            return "订单已支付";
        }
        if (ORDER_STATUS_CLOSED.equals(status)) {
            return "订单已关闭";
        }
        return "订单状态已更新";
    }

    /**
     * 解析订单状态更新时间。
     */
    private Instant resolveOrderUpdatedAt(Map<String, Object> order) {
        Instant payTime = asInstant(order.get("payTime"));
        if (payTime != null) {
            return payTime;
        }
        Instant closeTime = asInstant(order.get("closeTime"));
        if (closeTime != null) {
            return closeTime;
        }
        return asInstant(order.get("orderTime"));
    }
}
