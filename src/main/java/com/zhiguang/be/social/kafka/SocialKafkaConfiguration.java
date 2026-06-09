package com.zhiguang.be.social.kafka;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 社交模块 Kafka 配置。
 * 当前主要提供手动确认位点的监听容器，
 * 供聚合消费者和灾难回放消费者使用。
 */
@Configuration
@EnableKafka
public class SocialKafkaConfiguration {

    /**
     * 手动确认位点的 Kafka 监听容器。
     *
     * @param configurer Spring Boot 默认 Kafka 配置器
     * @param consumerFactory 消费者工厂
     * @return 手动确认位点监听容器
     */
    @Bean("kafkaManualAckListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaManualAckListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setMissingTopicsFatal(false);
        return factory;
    }

    /**
     * 计数灾难回放专用监听容器。
     * 回放链路宁可阻塞重试，也不能在 Redis 写失败时静默跳过 offset。
     */
    @Bean("kafkaCounterRebuildListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaCounterRebuildListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory
    ) {
        return blockingRetryListenerContainerFactory(configurer, consumerFactory);
    }

    /**
     * 关系 Outbox 专用监听容器。
     * 关系投影失败时必须保留 offset，避免 follower、缓存和计数等伪从静默漂移。
     */
    @Bean("kafkaRelationOutboxListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaRelationOutboxListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory
    ) {
        return blockingRetryListenerContainerFactory(configurer, consumerFactory);
    }

    /**
     * 构造失败阻塞重试的监听容器，适用于不能跳过 offset 的修复/投影链路。
     */
    private ConcurrentKafkaListenerContainerFactory<Object, Object> blockingRetryListenerContainerFactory(
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

    /**
     * 失败阻塞重试策略，显式使用无限尝试，防止关键投影链路被默认恢复器吞掉 offset。
     */
    static BackOff blockingRetryBackOff() {
        return new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS);
    }
}
