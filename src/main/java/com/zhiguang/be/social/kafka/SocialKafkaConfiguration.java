package com.zhiguang.be.social.kafka;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

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
}
