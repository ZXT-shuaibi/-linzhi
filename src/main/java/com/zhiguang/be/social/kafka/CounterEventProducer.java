package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
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
    private final boolean synchronousSend;
    private final long sendTimeoutMillis;

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
            @Value("${social.counter.kafka.enabled:false}") boolean enabled,
            @Value("${social.counter.kafka.sync-send:true}") boolean synchronousSend,
            @Value("${social.counter.kafka.send-timeout-ms:3000}") long sendTimeoutMillis
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.synchronousSend = synchronousSend;
        this.sendTimeoutMillis = Math.max(1L, sendTimeoutMillis);
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
            CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(CounterTopics.EVENTS, key, payload);
            if (synchronousSend) {
                sendFuture.get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                // The committed outbox event remains the recovery source if broker delivery later fails.
                sendFuture.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("async counter event delivery failed, entityType={}, entityId={}",
                                event.getEntityType(), event.getEntityId(), ex);
                    }
                });
            }
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
