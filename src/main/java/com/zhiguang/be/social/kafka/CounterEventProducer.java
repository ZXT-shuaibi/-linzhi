package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 计数事件 Kafka 生产者。
 * 当前默认关闭，只在显式开启后把互动计数事件发送到 Kafka，
 * 供聚合消费者和灾难回放链使用。
 */
@Service
public class CounterEventProducer {

    private static final Logger log = LoggerFactory.getLogger(CounterEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    /**
     * 构造计数事件生产者。
     *
     * @param kafkaTemplate Kafka 模板
     * @param objectMapper JSON 组件
     * @param enabled 是否开启 Kafka 计数链路
     */
    public CounterEventProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${social.counter.kafka.enabled:false}") boolean enabled
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * 发布计数事件到 Kafka。
     *
     * @param event 计数事件
     */
    public boolean publish(CounterEvent event) {
        if (!enabled || event == null) {
            return false;
        }

        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = event.getEntityType() + ":" + event.getEntityId();
            kafkaTemplate.send(CounterTopics.EVENTS, key, payload)
                    .get(3, TimeUnit.SECONDS);
            return true;
        } catch (JsonProcessingException ex) {
            log.warn("serialize counter event failed, entityType={}, entityId={}",
                    event.getEntityType(), event.getEntityId(), ex);
            return false;
        } catch (Exception ex) {
            log.warn("publish counter event to kafka failed, entityType={}, entityId={}",
                    event.getEntityType(), event.getEntityId(), ex);
            return false;
        }
    }

    /**
     * 返回当前是否开启 Kafka 计数链路。
     *
     * @return 开启返回 true，否则返回 false
     */
    public boolean isEnabled() {
        return enabled;
    }
}
