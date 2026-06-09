package com.zhiguang.be.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.content.model.PostSyncPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchCanalOutboxConsumerTest {

    private ObjectMapper objectMapper;
    private SearchIndexService searchIndexService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private Acknowledgment acknowledgment;
    private SearchCanalOutboxConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        searchIndexService = mock(SearchIndexService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        acknowledgment = mock(Acknowledgment.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey("dedup:search-post:event-1")).thenReturn(false, true);
        when(redisTemplate.hasKey("dedup:search-post:event-2")).thenReturn(false);
        when(redisTemplate.hasKey("dedup:search-post:event-3")).thenReturn(true);
        when(redisTemplate.hasKey("dedup:search-post:event-4")).thenReturn(false);
        when(valueOperations.setIfAbsent(
                eq("dedup:search-post:event-1"),
                eq("1"),
                eq(Duration.ofMinutes(10))
        )).thenReturn(true, false);
        when(valueOperations.setIfAbsent(
                eq("dedup:search-post:event-2"),
                eq("1"),
                eq(Duration.ofMinutes(10))
        )).thenReturn(true);
        when(valueOperations.setIfAbsent(
                eq("dedup:search-post:event-3"),
                eq("1"),
                eq(Duration.ofMinutes(10))
        )).thenReturn(false);
        when(valueOperations.setIfAbsent(
                eq("dedup:search-post:event-4"),
                eq("1"),
                eq(Duration.ofMinutes(10))
        )).thenReturn(null);
        when(redisTemplate.hasKey("dedup:search-post:event-5")).thenReturn(false);
        when(valueOperations.setIfAbsent(
                eq("dedup:search-post:event-5"),
                eq("1"),
                eq(Duration.ofMinutes(10))
        )).thenThrow(new IllegalStateException("redis down"));
        consumer = new SearchCanalOutboxConsumer(objectMapper, searchIndexService, redisTemplate, 10L);
    }

    @Test
    void onMessageShouldProjectTopChangedEvents() throws Exception {
        consumer.onMessage(outboxMessage("event-1", "POST_TOP_CHANGED", "1001"), acknowledgment);

        InOrder inOrder = inOrder(searchIndexService, valueOperations);
        inOrder.verify(searchIndexService).syncPostStrict(1001L);
        inOrder.verify(valueOperations).setIfAbsent("dedup:search-post:event-1", "1", Duration.ofMinutes(10));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void projectionFailureShouldThrowAndNotAck() throws Exception {
        doThrow(new IllegalStateException("es unavailable"))
                .when(searchIndexService)
                .syncPostStrict(anyLong());

        assertThrows(
                IllegalStateException.class,
                () -> consumer.onMessage(outboxMessage("event-2", "POST_PUBLISHED", "1002"), acknowledgment)
        );

        verify(valueOperations, never()).setIfAbsent(
                eq("dedup:search-post:event-2"),
                eq("1"),
                eq(Duration.ofMinutes(10))
        );
        verify(redisTemplate, never()).delete("dedup:search-post:event-2");
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void duplicateEventIdShouldAckAndSkipProjection() throws Exception {
        consumer.onMessage(outboxMessage("event-3", "POST_PUBLISHED", "1003"), acknowledgment);

        verify(searchIndexService, never()).syncPostStrict(anyLong());
        verify(redisTemplate).hasKey("dedup:search-post:event-3");
        verify(redisTemplate, never()).delete("dedup:search-post:event-3");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void nullDedupResultShouldThrowAndNotAck() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () -> consumer.onMessage(outboxMessage("event-4", "POST_PUBLISHED", "1004"), acknowledgment)
        );

        verify(searchIndexService).syncPostStrict(1004L);
        verify(redisTemplate).delete("dedup:search-post:event-4");
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void thrownDedupWriteShouldReleaseMarkerAndNotAck() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () -> consumer.onMessage(outboxMessage("event-5", "POST_PUBLISHED", "1005"), acknowledgment)
        );

        verify(searchIndexService).syncPostStrict(1005L);
        verify(redisTemplate).delete("dedup:search-post:event-5");
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void duplicateRowsInSameBatchShouldProjectOnceAndAck() throws Exception {
        String payload = objectMapper.writeValueAsString(new PostSyncPayload(
                "event-1",
                "POST_PUBLISHED",
                "1001",
                Instant.now()
        ));
        String message = objectMapper.writeValueAsString(Map.of(
                "table", "outbox",
                "type", "INSERT",
                "data", List.of(
                        Map.of(
                                "aggregate_type", "post",
                                "event_type", "POST_PUBLISHED",
                                "payload", payload
                        ),
                        Map.of(
                                "aggregate_type", "post",
                                "event_type", "POST_PUBLISHED",
                                "payload", payload
                        )
                )
        ));

        consumer.onMessage(message, acknowledgment);

        verify(searchIndexService, times(1)).syncPostStrict(1001L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void kafkaListenerShouldUseSearchOutboxSpecificFactory() throws Exception {
        KafkaListener listener = SearchCanalOutboxConsumer.class
                .getMethod("onMessage", String.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);

        assertEquals("kafkaSearchOutboxListenerContainerFactory", listener.containerFactory());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchOutboxListenerFactoryShouldUseManualAckAndNonAckingErrorHandler() throws Exception {
        SearchKafkaConfiguration configuration = new SearchKafkaConfiguration();
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        doAnswer(invocation -> null).when(configurer).configure(any(), eq(consumerFactory));

        Method factoryMethod = SearchKafkaConfiguration.class.getDeclaredMethod(
                "kafkaSearchOutboxListenerContainerFactory",
                ConcurrentKafkaListenerContainerFactoryConfigurer.class,
                ConsumerFactory.class
        );
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                (ConcurrentKafkaListenerContainerFactory<Object, Object>)
                        factoryMethod.invoke(configuration, configurer, consumerFactory);

        assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE, factory.getContainerProperties().getAckMode());
        CommonErrorHandler errorHandler = readCommonErrorHandler(factory);
        assertInstanceOf(DefaultErrorHandler.class, errorHandler);
        assertFalse(errorHandler.isAckAfterHandle());
    }

    @Test
    void searchOutboxBackOffShouldRetryWithoutAttemptLimit() {
        BackOff backOff = SearchKafkaConfiguration.blockingRetryBackOff();

        FixedBackOff fixedBackOff = assertInstanceOf(FixedBackOff.class, backOff);
        assertEquals(1000L, fixedBackOff.getInterval());
        assertEquals(FixedBackOff.UNLIMITED_ATTEMPTS, fixedBackOff.getMaxAttempts());
    }

    private String outboxMessage(String eventId, String eventType, String postId) throws Exception {
        String payload = objectMapper.writeValueAsString(new PostSyncPayload(
                eventId,
                eventType,
                postId,
                Instant.now()
        ));
        return objectMapper.writeValueAsString(Map.of(
                "table", "outbox",
                "type", "INSERT",
                "data", List.of(Map.of(
                        "aggregate_type", "post",
                        "event_type", eventType,
                        "payload", payload
                ))
        ));
    }

    private CommonErrorHandler readCommonErrorHandler(
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory
    ) throws Exception {
        Class<?> type = factory.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("commonErrorHandler");
                field.setAccessible(true);
                return (CommonErrorHandler) field.get(factory);
            } catch (NoSuchFieldException ex) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException("commonErrorHandler");
    }
}
