package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiguang.be.social.FollowEventPayload;
import com.zhiguang.be.social.service.impl.RelationEventProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CanalOutboxConsumerTest {

    private ObjectMapper objectMapper;
    private RelationEventProcessor relationEventProcessor;
    private Acknowledgment acknowledgment;
    private CanalOutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        relationEventProcessor = mock(RelationEventProcessor.class);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new CanalOutboxConsumer(objectMapper, relationEventProcessor);
    }

    @Test
    void relationProjectionFailureShouldRethrowAndNotAck() throws Exception {
        doThrow(new IllegalStateException("projection unavailable"))
                .when(relationEventProcessor)
                .process(any(FollowEventPayload.class));

        assertThrows(
                IllegalStateException.class,
                () -> consumer.onMessage(outboxMessage("FOLLOW_CREATED", followPayload("FOLLOW_CREATED")), acknowledgment)
        );

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void malformedRelationPayloadShouldThrowAndNotAck() throws Exception {
        assertThrows(
                Exception.class,
                () -> consumer.onMessage(outboxMessage("FOLLOW_CREATED", "{bad-json"), acknowledgment)
        );

        verifyNoInteractions(relationEventProcessor);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void nonRelationOutboxRowsShouldAckAndSkipProjection() throws Exception {
        assertDoesNotThrow(() -> consumer.onMessage(outboxMessage("POST_PUBLISHED", "{}"), acknowledgment));

        verifyNoInteractions(relationEventProcessor);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void nonOutboxMessagesShouldAckAndSkipProjection() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("table", "know_posts");
        root.put("type", "INSERT");
        root.putArray("data").addObject().put("event_type", "FOLLOW_CREATED");

        assertDoesNotThrow(() -> consumer.onMessage(objectMapper.writeValueAsString(root), acknowledgment));

        verifyNoInteractions(relationEventProcessor);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void kafkaListenerShouldUseRelationOutboxSpecificFactory() throws Exception {
        KafkaListener listener = CanalOutboxConsumer.class
                .getMethod("onMessage", String.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);

        assertEquals("kafkaRelationOutboxListenerContainerFactory", listener.containerFactory());
    }

    @Test
    void relationOutboxListenerFactoryShouldUseManualAckAndNonAckingErrorHandler() throws Exception {
        SocialKafkaConfiguration configuration = new SocialKafkaConfiguration();
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        doAnswer(invocation -> null).when(configurer).configure(any(), eq(consumerFactory));

        Method factoryMethod = SocialKafkaConfiguration.class.getDeclaredMethod(
                "kafkaRelationOutboxListenerContainerFactory",
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
    void relationOutboxBackOffShouldRetryWithoutAttemptLimit() {
        BackOff backOff = SocialKafkaConfiguration.blockingRetryBackOff();

        FixedBackOff fixedBackOff = assertInstanceOf(FixedBackOff.class, backOff);
        assertEquals(1000L, fixedBackOff.getInterval());
        assertEquals(FixedBackOff.UNLIMITED_ATTEMPTS, fixedBackOff.getMaxAttempts());
    }

    private String followPayload(String eventType) throws Exception {
        FollowEventPayload payload = FollowEventPayload.of(1001L, eventType, 10L, 20L, 9001L);
        return objectMapper.writeValueAsString(payload);
    }

    private String outboxMessage(String eventType, String payload) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("table", "outbox");
        root.put("type", "INSERT");
        ArrayNode data = root.putArray("data");
        ObjectNode row = data.addObject();
        row.put("event_type", eventType);
        row.put("payload", payload);
        return objectMapper.writeValueAsString(root);
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
