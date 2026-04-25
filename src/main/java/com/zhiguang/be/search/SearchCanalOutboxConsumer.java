package com.zhiguang.be.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.content.model.PostSyncPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 搜索索引 Canal outbox 消费器。
 * 监听内容模块写出的 post outbox 事件，把内容变更投影到 ES 索引。
 */
@Service
@ConditionalOnBean(SearchIndexService.class)
@ConditionalOnProperty(name = "search.outbox.kafka-enabled", havingValue = "true")
public class SearchCanalOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(SearchCanalOutboxConsumer.class);

    private final ObjectMapper objectMapper;
    private final SearchIndexService searchIndexService;

    public SearchCanalOutboxConsumer(ObjectMapper objectMapper, SearchIndexService searchIndexService) {
        this.objectMapper = objectMapper;
        this.searchIndexService = searchIndexService;
    }

    /**
     * 消费 Canal outbox 消息。
     * 只处理 outbox 表中的内容事件；投影失败时不确认位点，交给 Kafka 重试。
     */
    @KafkaListener(
            topics = "${search.outbox.topic:canal-outbox}",
            groupId = "${search.outbox.group-id:search-index-consumer}",
            containerFactory = "kafkaManualAckListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) {
        List<JsonNode> rows = extractOutboxRows(message);
        if (rows.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        for (JsonNode row : rows) {
            JsonNode aggregateTypeNode = row.get("aggregate_type");
            if (aggregateTypeNode != null && !"post".equalsIgnoreCase(aggregateTypeNode.asText())) {
                continue;
            }

            JsonNode eventTypeNode = row.get("event_type");
            String eventType = eventTypeNode == null ? null : eventTypeNode.asText();
            if (!isPostEvent(eventType)) {
                continue;
            }

            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null) {
                continue;
            }

            try {
                PostSyncPayload payload = objectMapper.readValue(payloadNode.asText(), PostSyncPayload.class);
                Long postId = parsePostId(payload.postId());
                if (postId == null) {
                    continue;
                }
                if ("POST_DELETED".equals(payload.eventType())) {
                    searchIndexService.deletePost(postId);
                } else {
                    searchIndexService.syncPost(postId);
                }
            } catch (Exception ex) {
                log.warn("搜索 outbox 投影失败，message={}", message, ex);
                return;
            }
        }

        acknowledgment.acknowledge();
    }

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
            log.warn("搜索 Canal outbox 消息解析失败，message={}", message, ex);
            return Collections.emptyList();
        }
    }

    private boolean isPostEvent(String eventType) {
        return "POST_PUBLISHED".equals(eventType)
                || "POST_VISIBILITY_CHANGED".equals(eventType)
                || "POST_DELETED".equals(eventType);
    }

    private Long parsePostId(String postId) {
        if (postId == null || postId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(postId.trim());
        } catch (Exception ex) {
            return null;
        }
    }
}
