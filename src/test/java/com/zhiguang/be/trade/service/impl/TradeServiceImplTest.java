package com.zhiguang.be.trade.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.trade.TradeRedisKeys;
import com.zhiguang.be.trade.kafka.TradeOrderEvent;
import com.zhiguang.be.trade.kafka.TradeOrderProducer;
import com.zhiguang.be.trade.mapper.TradeMapper;
import com.zhiguang.be.trade.model.TradeActivityListData;
import com.zhiguang.be.trade.model.TradeOrderPageData;
import com.zhiguang.be.trade.model.TradeSubmitData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeServiceImplTest {

    private TradeMapper tradeMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SnowflakeIdGenerator snowflakeIdGenerator;
    private TradeOrderProducer tradeOrderProducer;
    private TradeServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tradeMapper = mock(TradeMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
        tradeOrderProducer = mock(TradeOrderProducer.class);
        CacheService cacheService = mock(CacheService.class);
        Executor directExecutor = Runnable::run;

        doReturn(valueOperations).when(redisTemplate).opsForValue();
        doAnswer(invocation -> 1L)
                .when(redisTemplate)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        service = new TradeServiceImpl(
                tradeMapper,
                redisTemplate,
                cacheService,
                snowflakeIdGenerator,
                tradeOrderProducer,
                directExecutor,
                new ObjectMapper().findAndRegisterModules(),
                transactionManager()
        );

        ReflectionTestUtils.setField(service, "closeBatchSize", 100);
        ReflectionTestUtils.setField(service, "orderStatusTtlHours", 48);
        ReflectionTestUtils.setField(service, "activityTtlSeconds", 300L);
        ReflectionTestUtils.setField(service, "jitterSeconds", 0L);
    }

    @Test
    void placeOrderShouldUseRequestedQuantityForPreDeductAndEvent() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(tradeMapper.findActivityById(1001L)).thenReturn(activityRow(1001L, 5, 20));
        when(snowflakeIdGenerator.nextId()).thenReturn(9001001L);
        when(tradeOrderProducer.publish(any(TradeOrderEvent.class))).thenReturn(true);

        TradeSubmitData result = service.placeOrder(7L, 1001L, 2);

        assertEquals("9001001", result.orderNo());

        ArgumentCaptor<TradeOrderEvent> eventCaptor = ArgumentCaptor.forClass(TradeOrderEvent.class);
        verify(tradeOrderProducer).publish(eventCaptor.capture());
        assertEquals(2, eventCaptor.getValue().quantity());

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, atLeastOnce()).execute(any(DefaultRedisScript.class), anyList(), argsCaptor.capture());
        Object[] precheckArgs = argsCaptor.getAllValues().stream()
                .filter(args -> args.length == 4)
                .findFirst()
                .orElseThrow();
        assertEquals("2", precheckArgs[1]);
    }

    @Test
    void acceptOrderEventShouldReleaseRedisReservationWhenCancelledBeforeCreate() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.hasKey(TradeRedisKeys.orderCancelMarkerKey(7L, "order-1"))).thenReturn(true);
        when(tradeMapper.findActivityById(1001L)).thenReturn(activityRow(1001L, 5, 12));

        service.acceptOrderEvent(new TradeOrderEvent("order-1", 1001L, 7L, 2, Instant.now()));

        verify(tradeMapper, never()).insertOrder(
                anyLong(),
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                any(),
                anyString(),
                any(),
                any()
        );

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, atLeastOnce()).execute(any(DefaultRedisScript.class), keysCaptor.capture(), argsCaptor.capture());

        int matched = 0;
        for (int i = 0; i < argsCaptor.getAllValues().size(); i++) {
            Object[] args = argsCaptor.getAllValues().get(i);
            List<String> keys = keysCaptor.getAllValues().get(i);
            if (args.length == 4 && keys.size() == 2 && TradeRedisKeys.activityStockKey(1001L).equals(keys.get(0))) {
                matched++;
                assertEquals("5", args[0]);
                assertEquals("7", args[1]);
                assertEquals("2", args[2]);
            }
        }
        assertEquals(1, matched);
    }

    @Test
    void listMyOrdersShouldSupportStatusFilter() {
        when(tradeMapper.listExpiredPendingOrdersByBuyer(eq(7L), any(Instant.class), eq(100))).thenReturn(List.of());
        when(tradeMapper.listOrdersByBuyer(7L, "PAID", 10, 0)).thenReturn(List.of(orderRow("PAID")));
        when(tradeMapper.countOrdersByBuyer(7L, "PAID")).thenReturn(1L);

        TradeOrderPageData page = service.listMyOrders(7L, "paid", 1, 10);

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        assertEquals("PAID", page.items().get(0).status());
        verify(tradeMapper).listOrdersByBuyer(7L, "PAID", 10, 0);
        verify(tradeMapper).countOrdersByBuyer(7L, "PAID");
    }

    @Test
    void listActivitiesShouldSupportStageFilter() {
        when(tradeMapper.listPublicActivities(any(Instant.class), eq("active"), eq(20), eq(0)))
                .thenReturn(List.of(activityRow(1001L, 5, 20)));
        when(tradeMapper.countPublicActivities(any(Instant.class), eq("active"))).thenReturn(1L);
        when(valueOperations.get(TradeRedisKeys.activityStockKey(1001L))).thenReturn("5");

        TradeActivityListData page = service.listActivities("active", 1, 20);

        assertEquals(1, page.total());
        assertFalse(page.items().isEmpty());
        assertEquals("1001", page.items().get(0).activityId());
        assertEquals(5, page.items().get(0).availableStock());
        assertTrue(page.items().get(0).active());
    }

    private Map<String, Object> activityRow(long activityId, int availableStock, int totalStock) {
        Instant now = Instant.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("activityId", activityId);
        row.put("title", "Trade activity");
        row.put("description", "Trade activity description");
        row.put("cover", "https://cdn.example/activity.png");
        row.put("originalPrice", new BigDecimal("99.00"));
        row.put("seckillPrice", new BigDecimal("49.00"));
        row.put("totalStock", totalStock);
        row.put("availableStock", availableStock);
        row.put("perUserLimit", 5);
        row.put("status", "published");
        row.put("beginTime", now.minus(Duration.ofHours(1)));
        row.put("endTime", now.plus(Duration.ofHours(3)));
        row.put("payTimeoutMinutes", 15);
        row.put("version", 0L);
        return row;
    }

    private Map<String, Object> orderRow(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderNo", "order-1");
        row.put("activityId", 1001L);
        row.put("activityTitle", "Trade activity");
        row.put("activityCover", "https://cdn.example/activity.png");
        row.put("amount", new BigDecimal("49.00"));
        row.put("quantity", 1);
        row.put("status", status);
        row.put("payChannel", "mock");
        row.put("orderTime", Instant.now().minus(Duration.ofMinutes(2)));
        row.put("expireAt", Instant.now().plus(Duration.ofMinutes(13)));
        row.put("payTime", Instant.now().minus(Duration.ofMinutes(1)));
        row.put("closeTime", null);
        row.put("closeReason", null);
        return row;
    }

    private PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
