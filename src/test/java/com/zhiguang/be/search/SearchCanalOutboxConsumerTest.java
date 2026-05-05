package com.zhiguang.be.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.content.model.PostSyncPayload;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SearchCanalOutboxConsumerTest {

    @Test
    void onMessageShouldProjectTopChangedEvents() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SearchIndexService searchIndexService = mock(SearchIndexService.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        SearchCanalOutboxConsumer consumer = new SearchCanalOutboxConsumer(objectMapper, searchIndexService);

        String payload = objectMapper.writeValueAsString(new PostSyncPayload(
                "event-1",
                "POST_TOP_CHANGED",
                "1001",
                Instant.now()
        ));
        String message = objectMapper.writeValueAsString(Map.of(
                "table", "outbox",
                "type", "INSERT",
                "data", List.of(Map.of(
                        "aggregate_type", "post",
                        "event_type", "POST_TOP_CHANGED",
                        "payload", payload
                ))
        ));

        consumer.onMessage(message, acknowledgment);

        verify(searchIndexService).syncPostStrict(1001L);
        verify(acknowledgment).acknowledge();
    }
}
