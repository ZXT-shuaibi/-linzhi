package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.social.SocialCounterSchema;
import com.zhiguang.be.social.service.CounterAggregationOperations;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CounterAggregationConsumerTest {

    @Test
    void onMessageShouldRethrowWhenAggregationFails() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CounterAggregationOperations operations = mock(CounterAggregationOperations.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        CounterAggregationConsumer consumer = new CounterAggregationConsumer(objectMapper, operations);
        CounterEvent event = CounterEvent.of(
                "post",
                "1001",
                "like",
                SocialCounterSchema.IDX_LIKE,
                7L,
                1,
                "counter-event-1"
        );
        String message = objectMapper.writeValueAsString(event);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(operations).acceptAggregateEvent(any(CounterEvent.class));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message, acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }
}
