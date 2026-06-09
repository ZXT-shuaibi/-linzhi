package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.util.OutboxMessageUtil;
import com.zhiguang.be.social.FollowEventPayload;
import com.zhiguang.be.social.service.impl.RelationEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Canal outbox 消费器。
 * 监听 Canal 转发到 Kafka 的 outbox 表变更，再把关系事件交给投影处理器。
 */
@Service
@ConditionalOnProperty(name = "social.relation.outbox.kafka-enabled", havingValue = "true")
public class CanalOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(CanalOutboxConsumer.class);

    private final ObjectMapper objectMapper;
    private final RelationEventProcessor relationEventProcessor;

    public CanalOutboxConsumer(ObjectMapper objectMapper, RelationEventProcessor relationEventProcessor) {
        this.objectMapper = objectMapper;
        this.relationEventProcessor = relationEventProcessor;
    }

    /**
     * 消费 Canal outbox 消息。
     * 非关系事件会直接跳过；投影失败时不确认位点，交给 Kafka 重试。
     */
    @KafkaListener(
            topics = "${social.relation.outbox.topic:${canal.topic:canal-outbox}}",
            groupId = "${social.relation.outbox.group-id:relation-outbox-consumer}",
            containerFactory = "kafkaRelationOutboxListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
        if (rows.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        for (JsonNode row : rows) {
            JsonNode eventTypeNode = row.get("event_type");
            if (eventTypeNode != null && !isRelationEvent(eventTypeNode.asText())) {
                continue;
            }

            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null) {
                continue;
            }

            try {
                FollowEventPayload payload = objectMapper.readValue(payloadNode.asText(), FollowEventPayload.class);
                if (!isRelationEvent(payload.getEventType())) {
                    continue;
                }
                relationEventProcessor.process(payload);
            } catch (JsonProcessingException ex) {
                log.warn("关系 outbox payload 解析失败，payload={}", payloadNode.asText(), ex);
                throw ex;
            } catch (Exception ex) {
                log.warn("关系 outbox 投影失败，message={}", message, ex);
                throw ex;
            }
        }

        acknowledgment.acknowledge();
    }

    private boolean isRelationEvent(String eventType) {
        return "FOLLOW_CREATED".equals(eventType)
                || "FOLLOW_REMOVED".equals(eventType)
                || "FOLLOW_CANCELED".equals(eventType)
                || "FollowCreated".equals(eventType)
                || "FollowCanceled".equals(eventType);
    }
}
