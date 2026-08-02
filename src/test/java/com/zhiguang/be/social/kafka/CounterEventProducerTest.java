package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.social.SocialCounterSchema;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CounterEventProducerTest {

    @Test
    @SuppressWarnings("unchecked")
    void asyncPublishShouldNotWaitForKafkaAcknowledgement() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> acknowledgement = new CompletableFuture<SendResult<String, String>>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acknowledgement);

        CounterEventProducer producer = new CounterEventProducer(
                kafkaTemplate,
                new ObjectMapper(),
                true,
                false,
                3_000L
        );

        boolean accepted = producer.publish(CounterEvent.of(
                "post", "1001", "like", SocialCounterSchema.IDX_LIKE, 7L, 1, "event-1001"
        ));

        assertTrue(accepted);
    }
}
