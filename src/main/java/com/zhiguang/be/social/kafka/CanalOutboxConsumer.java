package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.social.FollowEventPayload;
import com.zhiguang.be.social.service.impl.RelationEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Canal Outbox 消费器。
 * 监听 Canal 转发到 Kafka 的 outbox 表变更，把关系事件交给投影处理器更新 follower、Redis 缓存和计数。
 */
@Service
@ConditionalOnProperty(name = "social.relation.outbox.kafka-enabled", havingValue = "true")
public class CanalOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(CanalOutboxConsumer.class);

    private final ObjectMapper objectMapper;
    private final RelationEventProcessor relationEventProcessor;

    /**
     * 构造 Canal Outbox 消费器。
     *
     * @param objectMapper JSON 解析组件
     * @param relationEventProcessor 关系事件投影处理器
     */
    public CanalOutboxConsumer(ObjectMapper objectMapper, RelationEventProcessor relationEventProcessor) {
        this.objectMapper = objectMapper;
        this.relationEventProcessor = relationEventProcessor;
    }

    /**
     * 消费 Canal outbox 消息。
     * 非 outbox 表、非 INSERT/UPDATE 消息会直接确认；投影失败时不确认位点，交给 Kafka 重试。
     *
     * @param message Kafka 消息内容
     * @param acknowledgment 手动位点确认对象
     */
    @KafkaListener(
            topics = "${social.relation.outbox.topic:canal-outbox}",
            groupId = "${social.relation.outbox.group-id:relation-outbox-consumer}",
            containerFactory = "kafkaManualAckListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) {
        List<JsonNode> rows = extractOutboxRows(message);
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
            } catch (Exception ex) {
                log.warn("关系 outbox 投影失败，message={}", message, ex);
                return;
            }
        }

        acknowledgment.acknowledge();
    }

    /**
     * 从 Canal 消息中提取 outbox 表变更行。
     *
     * @param message Canal JSON 消息
     * @return outbox 行列表
     */
    private List<JsonNode> extractOutboxRows(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode table = root.get("table");
            if (table == null || !"outbox".equalsIgnoreCase(table.asText())) {
                return Collections.emptyList();
            }

            JsonNode type = root.get("type");
            if (type == null || (!"INSERT".equalsIgnoreCase(type.asText()) && !"UPDATE".equalsIgnoreCase(type.asText()))) {
                return Collections.emptyList();
            }

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }

            List<JsonNode> rows = new ArrayList<JsonNode>();
            for (JsonNode row : data) {
                rows.add(row);
            }
            return rows;
        } catch (Exception ex) {
            log.warn("Canal outbox 消息解析失败，message={}", message, ex);
            return Collections.emptyList();
        }
    }

    /**
     * 判断是否为关系投影事件。
     *
     * @param eventType 事件类型
     * @return 是关系事件返回 true
     */
    private boolean isRelationEvent(String eventType) {
        return "FOLLOW_CREATED".equals(eventType)
                || "FOLLOW_REMOVED".equals(eventType)
                || "FOLLOW_CANCELED".equals(eventType)
                || "FollowCreated".equals(eventType)
                || "FollowCanceled".equals(eventType);
    }
}
