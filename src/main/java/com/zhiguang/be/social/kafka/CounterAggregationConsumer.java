package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.social.service.CounterAggregationOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 计数事件聚合消费者。
 * 负责消费互动计数事件，先将增量写入 Redis 聚合桶，
 * 再按固定周期把聚合桶折叠进实体计数快照。
 */
@Service
@ConditionalOnProperty(name = "social.counter.kafka.enabled", havingValue = "true")
public class CounterAggregationConsumer {

    private static final Logger log = LoggerFactory.getLogger(CounterAggregationConsumer.class);

    private final ObjectMapper objectMapper;
    private final CounterAggregationOperations counterAggregationOperations;

    /**
     * 构造计数聚合消费者。
     *
     * @param objectMapper Jackson 组件
     * @param interactionService 互动服务实现
     */
    public CounterAggregationConsumer(ObjectMapper objectMapper, CounterAggregationOperations counterAggregationOperations) {
        this.objectMapper = objectMapper;
        this.counterAggregationOperations = counterAggregationOperations;
    }

    /**
     * 消费 Kafka 计数事件并写入聚合桶。
     * 只有聚合桶写入成功后才提交位点，避免消息丢失。
     *
     * @param message Kafka 消息体
     * @param acknowledgment 手动位点确认器
     * @throws Exception 反序列化异常交给容器处理
     */
    @KafkaListener(
            topics = CounterTopics.EVENTS,
            groupId = "counter-agg",
            containerFactory = "kafkaManualAckListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        CounterEvent event = objectMapper.readValue(message, CounterEvent.class);
        try {
            counterAggregationOperations.acceptAggregateEvent(event);
            acknowledgment.acknowledge();
        } catch (Exception ex) {
            log.warn("consume counter event failed, entityType={}, entityId={}, metric={}",
                    event.getEntityType(), event.getEntityId(), event.getMetric(), ex);
        }
    }

    /**
     * 周期性折叠聚合桶。
     * Kafka 模式开启后，由该消费者负责触发聚合桶刷写。
     */
    @Scheduled(fixedDelayString = "${social.counter.kafka.flush-delay-ms:1000}")
    public void flush() {
        counterAggregationOperations.flushAggregateBucketsNow();
    }
}
