package com.zhiguang.be.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.util.OutboxMessageUtil;
import com.zhiguang.be.content.model.PostSyncPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 搜索索引 Canal outbox 消费器。
 * 监听内容模块的 post outbox 事件，再把内容变更投影到 ES 索引。
 */
@Service
@ConditionalOnBean(SearchIndexService.class)
@ConditionalOnProperty(name = "search.outbox.kafka-enabled", havingValue = "true")
public class SearchCanalOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(SearchCanalOutboxConsumer.class);

    private final ObjectMapper objectMapper;
    private final SearchIndexService searchIndexService;
    private final StringRedisTemplate stringRedisTemplate;
    private final Duration dedupTtl;

    public SearchCanalOutboxConsumer(
            ObjectMapper objectMapper,
            SearchIndexService searchIndexService,
            StringRedisTemplate stringRedisTemplate,
            @Value("${search.outbox.dedup-ttl-minutes:10}") long dedupTtlMinutes
    ) {
        this.objectMapper = objectMapper;
        this.searchIndexService = searchIndexService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.dedupTtl = Duration.ofMinutes(Math.max(1L, dedupTtlMinutes));
    }

    /**
     * 消费 Canal outbox 消息。
     * 只处理 post 相关事件；投影失败时保留位点，让 Kafka 重试。
     */
    @KafkaListener(
            topics = "${search.outbox.topic:${canal.topic:canal-outbox}}",
            groupId = "${search.outbox.group-id:search-index-consumer}",
            containerFactory = "kafkaSearchOutboxListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
        if (rows.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        Set<String> seenInMessage = new HashSet<String>();
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
                String eventId = resolveEventId(payload);
                if (isAlreadyProjected(eventId, seenInMessage)) {
                    continue;
                }
                if ("POST_DELETED".equals(payload.eventType())) {
                    searchIndexService.deletePostStrict(postId);
                } else {
                    searchIndexService.syncPostStrict(postId);
                }
                markProjected(eventId);
            } catch (Exception ex) {
                log.warn("搜索 outbox 投影失败，message={}", message, ex);
                throw ex;
            }
        }

        acknowledgment.acknowledge();
    }

    private boolean isPostEvent(String eventType) {
        return "POST_PUBLISHED".equals(eventType)
                || "POST_VISIBILITY_CHANGED".equals(eventType)
                || "POST_TOP_CHANGED".equals(eventType)
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

    private boolean hasEventId(PostSyncPayload payload) {
        return payload.eventId() != null && !payload.eventId().isBlank();
    }

    private String resolveEventId(PostSyncPayload payload) {
        if (!hasEventId(payload)) {
            return null;
        }
        return payload.eventId().trim();
    }

    private boolean isAlreadyProjected(String eventId, Set<String> seenInMessage) {
        if (eventId == null) {
            return false;
        }
        if (!seenInMessage.add(eventId)) {
            return true;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(SearchRedisKeys.postOutboxDedupKey(eventId)));
    }

    /**
     * Marks an event after indexing succeeds so retry prefers duplicate projection over lost projection.
     */
    private void markProjected(String eventId) {
        if (eventId == null) {
            return;
        }
        Boolean first;
        try {
            first = stringRedisTemplate.opsForValue().setIfAbsent(
                    SearchRedisKeys.postOutboxDedupKey(eventId),
                    "1",
                    dedupTtl
            );
        } catch (RuntimeException ex) {
            rollbackEvent(eventId);
            throw ex;
        }
        if (first == null) {
            rollbackEvent(eventId);
            throw new IllegalStateException("search outbox dedup result is null, eventId=" + eventId);
        }
    }

    private void rollbackEvent(String eventId) {
        if (eventId != null && !eventId.isBlank()) {
            stringRedisTemplate.delete(SearchRedisKeys.postOutboxDedupKey(eventId.trim()));
        }
    }
}
