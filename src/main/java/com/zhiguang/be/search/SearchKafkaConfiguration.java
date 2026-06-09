package com.zhiguang.be.search;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka listener configuration for search index projection consumers.
 */
@Configuration
public class SearchKafkaConfiguration {

    /**
     * Search outbox projection must keep the offset uncommitted while ES indexing is unavailable.
     */
    @Bean("kafkaSearchOutboxListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaSearchOutboxListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setMissingTopicsFatal(false);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(blockingRetryBackOff());
        errorHandler.setAckAfterHandle(false);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    static BackOff blockingRetryBackOff() {
        return new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS);
    }
}
