package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.social.SocialCounterSchema;
import com.zhiguang.be.social.SocialRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.Acknowledgment;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CounterRebuildConsumerTest {

    private ObjectMapper objectMapper;
    private StringRedisTemplate redisTemplate;
    private Acknowledgment acknowledgment;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        redisTemplate = mock(StringRedisTemplate.class);
        acknowledgment = mock(Acknowledgment.class);
    }

    @Test
    void dryRunShouldAckAndSkipRedisWriteWhenEventIsInRange() throws Exception {
        CounterRebuildConsumer consumer = newConsumer("post", 1000L, 2000L, true);
        String message = message("post", "1001");

        consumer.onMessage(message, acknowledgment);

        verifyNoInteractions(redisTemplate);
        verify(acknowledgment).acknowledge();
        assertEquals(1L, consumer.getReceivedCount());
        assertEquals(0L, consumer.getAppliedCount());
        assertEquals(0L, consumer.getSkippedCount());
        assertEquals(1L, consumer.getDryRunCount());
        assertEquals(0L, consumer.getFailedCount());
    }

    @Test
    void targetTypeMismatchShouldAckAndMarkSkipped() throws Exception {
        CounterRebuildConsumer consumer = newConsumer("comment", -1L, -1L, false);
        String message = message("post", "1001");

        consumer.onMessage(message, acknowledgment);

        verifyNoInteractions(redisTemplate);
        verify(acknowledgment).acknowledge();
        assertEquals(1L, consumer.getReceivedCount());
        assertEquals(1L, consumer.getSkippedCount());
    }

    @Test
    void entityIdBelowMinShouldAckAndMarkSkipped() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, 1000L, -1L, false);
        String message = message("post", "999");

        consumer.onMessage(message, acknowledgment);

        verifyNoInteractions(redisTemplate);
        verify(acknowledgment).acknowledge();
        assertEquals(1L, consumer.getSkippedCount());
    }

    @Test
    void entityIdAboveMaxShouldAckAndMarkSkipped() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, 1000L, false);
        String message = message("post", "1001");

        consumer.onMessage(message, acknowledgment);

        verifyNoInteractions(redisTemplate);
        verify(acknowledgment).acknowledge();
        assertEquals(1L, consumer.getSkippedCount());
    }

    @Test
    void nonNumericEntityIdShouldSkipWhenRangeIsConfigured() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, 1000L, -1L, false);
        String message = message("post", "abc");

        consumer.onMessage(message, acknowledgment);

        verifyNoInteractions(redisTemplate);
        verify(acknowledgment).acknowledge();
        assertEquals(1L, consumer.getSkippedCount());
    }

    @Test
    void nullRedisScriptResultShouldThrowAndNotAck() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, -1L, false);
        String message = message("post", "1001");
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(rebuildKeys("counter-event-1", "post", "1001")),
                eq("1"),
                any(String.class),
                eq(String.valueOf(SocialCounterSchema.SCHEMA_LEN)),
                eq(String.valueOf(SocialCounterSchema.FIELD_SIZE)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message, acknowledgment));

        verify(acknowledgment, never()).acknowledge();
        assertEquals(1L, consumer.getFailedCount());
    }

    @Test
    void zeroRedisScriptResultShouldAckAsDuplicateAndNotApply() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, -1L, false);
        String message = message("post", "1001");
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(rebuildKeys("counter-event-1", "post", "1001")),
                eq("1"),
                any(String.class),
                eq(String.valueOf(SocialCounterSchema.SCHEMA_LEN)),
                eq(String.valueOf(SocialCounterSchema.FIELD_SIZE)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(0L);

        consumer.onMessage(message, acknowledgment);

        verify(acknowledgment).acknowledge();
        assertEquals(0L, consumer.getAppliedCount());
        assertEquals(1L, consumer.getDuplicateCount());
        assertEquals(0L, consumer.getFailedCount());
    }

    @Test
    void redisExceptionShouldRethrowAndNotAck() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, -1L, false);
        String message = message("post", "1001");
        doThrow(new IllegalStateException("redis unavailable")).when(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(rebuildKeys("counter-event-1", "post", "1001")),
                eq("1"),
                any(String.class),
                eq(String.valueOf(SocialCounterSchema.SCHEMA_LEN)),
                eq(String.valueOf(SocialCounterSchema.FIELD_SIZE)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        );

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message, acknowledgment));

        verify(acknowledgment, never()).acknowledge();
        assertEquals(1L, consumer.getFailedCount());
    }

    @Test
    void successfulRedisWriteShouldAckAndMarkApplied() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, -1L, false);
        String message = message("post", "1001");
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(rebuildKeys("counter-event-1", "post", "1001")),
                eq("1"),
                any(String.class),
                eq(String.valueOf(SocialCounterSchema.SCHEMA_LEN)),
                eq(String.valueOf(SocialCounterSchema.FIELD_SIZE)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(1L);

        consumer.onMessage(message, acknowledgment);

        verify(acknowledgment).acknowledge();
        assertEquals(1L, consumer.getReceivedCount());
        assertEquals(1L, consumer.getAppliedCount());
        assertEquals(0L, consumer.getFailedCount());
    }

    @Test
    void duplicateEventIdShouldAckWithoutApplyingDeltaTwice() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, -1L, false);
        String message = message("post", "1001");
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(rebuildKeys("counter-event-1", "post", "1001")),
                eq("1"),
                any(String.class),
                eq(String.valueOf(SocialCounterSchema.SCHEMA_LEN)),
                eq(String.valueOf(SocialCounterSchema.FIELD_SIZE)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(1L, 0L);

        consumer.onMessage(message, acknowledgment);
        consumer.onMessage(message, acknowledgment);

        verify(redisTemplate, times(2)).execute(
                any(DefaultRedisScript.class),
                eq(rebuildKeys("counter-event-1", "post", "1001")),
                eq("1"),
                any(String.class),
                eq(String.valueOf(SocialCounterSchema.SCHEMA_LEN)),
                eq(String.valueOf(SocialCounterSchema.FIELD_SIZE)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        );
        verify(acknowledgment, times(2)).acknowledge();
        assertEquals(2L, consumer.getReceivedCount());
        assertEquals(1L, consumer.getAppliedCount());
        assertEquals(1L, consumer.getDuplicateCount());
        assertEquals(0L, consumer.getFailedCount());
    }

    @Test
    void reversedEntityRangeShouldFailFast() {
        assertThrows(
                IllegalArgumentException.class,
                () -> newConsumer(null, 2000L, 1000L, false)
        );
    }

    @Test
    void invalidCounterIndexShouldThrowBeforeRedisWriteAndNotAck() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, -1L, false);
        String message = message("post", "1001", "like", SocialCounterSchema.SCHEMA_LEN, "counter-event-bad-idx");

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(message, acknowledgment));

        verify(redisTemplate, never()).execute(
                any(DefaultRedisScript.class),
                any(),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class)
        );
        verify(acknowledgment, never()).acknowledge();
        assertEquals(1L, consumer.getFailedCount());
    }

    @Test
    void negativeRedisScriptResultShouldThrowAndNotAck() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, -1L, false);
        String message = message("post", "1001");
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(rebuildKeys("counter-event-1", "post", "1001")),
                eq("1"),
                any(String.class),
                eq(String.valueOf(SocialCounterSchema.SCHEMA_LEN)),
                eq(String.valueOf(SocialCounterSchema.FIELD_SIZE)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(-2L);

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message, acknowledgment));

        verify(acknowledgment, never()).acknowledge();
        assertEquals(1L, consumer.getFailedCount());
    }

    @Test
    void rebuildLuaScriptShouldGuardInvalidIndexAndMalformedSds() throws Exception {
        CounterRebuildConsumer consumer = newConsumer(null, -1L, -1L, false);
        String scriptText = readScriptText(consumer);

        org.junit.jupiter.api.Assertions.assertTrue(scriptText.contains("idx < 0 or idx >= schemaLen"));
        org.junit.jupiter.api.Assertions.assertTrue(scriptText.contains("string.len(cnt) ~= schemaLen * fieldSize"));
    }

    @Test
    void kafkaListenerShouldUseConfigurableGroupAndRebuildSpecificFactory() throws Exception {
        KafkaListener listener = CounterRebuildConsumer.class
                .getMethod("onMessage", String.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);

        assertEquals("${social.rebuild.group-id:counter-rebuild}", listener.groupId());
        assertEquals("kafkaCounterRebuildListenerContainerFactory", listener.containerFactory());
    }

    @Test
    void counterRebuildListenerFactoryShouldUseManualAckAndErrorHandler() {
        SocialKafkaConfiguration configuration = new SocialKafkaConfiguration();
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        doAnswer(invocation -> null).when(configurer).configure(any(), eq(consumerFactory));

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                configuration.kafkaCounterRebuildListenerContainerFactory(configurer, consumerFactory);

        assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE, factory.getContainerProperties().getAckMode());
        assertDoesNotThrow(() -> {
            CommonErrorHandler errorHandler = readCommonErrorHandler(factory);
            org.junit.jupiter.api.Assertions.assertNotNull(errorHandler);
        });
    }

    private CounterRebuildConsumer newConsumer(String targetType, long entityIdMin, long entityIdMax, boolean dryRun) {
        return new CounterRebuildConsumer(objectMapper, redisTemplate, targetType, entityIdMin, entityIdMax, dryRun, 7L);
    }

    private String message(String entityType, String entityId) throws Exception {
        return message(entityType, entityId, "like", SocialCounterSchema.IDX_LIKE, "counter-event-1");
    }

    private String message(String entityType, String entityId, String metric, int idx, String eventId) throws Exception {
        CounterEvent event = CounterEvent.of(
                entityType,
                entityId,
                metric,
                idx,
                7L,
                1,
                eventId
        );
        return objectMapper.writeValueAsString(event);
    }

    private java.util.List<String> rebuildKeys(String eventId, String entityType, String entityId) {
        return java.util.Arrays.asList(
                SocialRedisKeys.counterRebuildDedupKey(eventId),
                SocialRedisKeys.entityCounterKey(entityType, entityId)
        );
    }

    private String readScriptText(CounterRebuildConsumer consumer) throws Exception {
        Field field = CounterRebuildConsumer.class.getDeclaredField("incrementFieldScript");
        field.setAccessible(true);
        DefaultRedisScript<Long> script = (DefaultRedisScript<Long>) field.get(consumer);
        return script.getScriptAsString();
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
