package com.zhiguang.be.trade.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 交易下单事件生产者。
 * Kafka 链关闭时返回 false，由业务层回退到本地异步执行。
 */
@Service
public class TradeOrderProducer {

    private static final Logger log = LoggerFactory.getLogger(TradeOrderProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    /**
     * 注入 Kafka 生产依赖。
     */
    public TradeOrderProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${trade.kafka.enabled:false}") boolean enabled
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * 发送下单事件。
     *
     * @return 发送成功返回 true；若 Kafka 链关闭或发送失败返回 false
     */
    public boolean publish(TradeOrderEvent event) {
        if (!enabled || event == null) {
            return false;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TradeTopics.ORDER_EVENTS, event.orderNo(), payload).get(3, TimeUnit.SECONDS);
            return true;
        } catch (JsonProcessingException ex) {
            log.warn("serialize trade order event failed, orderNo={}", event.orderNo(), ex);
            return false;
        } catch (Exception ex) {
            log.warn("publish trade order event failed, orderNo={}", event.orderNo(), ex);
            return false;
        }
    }
}
