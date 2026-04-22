package com.zhiguang.be.trade.kafka;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * 交易模块 Kafka 配置。
 * 提供手动确认位点容器，便于下单消费者在处理成功后再提交位点。
 */
@Configuration
public class TradeKafkaConfiguration {

    /**
     * 交易模块手动确认监听容器。
     */
    @Bean("tradeManualAckListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> tradeManualAckListenerContainerFactory(
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
