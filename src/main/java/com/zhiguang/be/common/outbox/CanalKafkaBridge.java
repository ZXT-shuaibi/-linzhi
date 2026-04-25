package com.zhiguang.be.common.outbox;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiguang.be.threadpool.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Canal 到 Kafka 的桥接器。
 * 监听 MySQL binlog 中的 outbox 表变更，并统一转发到 Kafka。
 */
@Service
public class CanalKafkaBridge implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CanalKafkaBridge.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CanalProperties canalProperties;
    private final ExecutorService executorService;

    private volatile boolean running;
    private volatile CanalConnector connector;

    public CanalKafkaBridge(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            CanalProperties canalProperties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.canalProperties = canalProperties;
        this.executorService = Executors.newSingleThreadExecutor(new NamedThreadFactory("canal-bridge-"));
    }

    @Override
    public void start() {
        if (running || !canalProperties.isEnabled()) {
            return;
        }
        running = true;
        executorService.execute(this::runLoop);
    }

    private void runLoop() {
        try {
            connector = CanalConnectors.newSingleConnector(
                    new InetSocketAddress(canalProperties.getHost(), canalProperties.getPort()),
                    canalProperties.getDestination(),
                    canalProperties.getUsername(),
                    canalProperties.getPassword()
            );
            connector.connect();
            connector.subscribe(canalProperties.getFilter());
            connector.rollback();
            log.info(
                    "Canal bridge started, host={}, port={}, destination={}, filter={}, topic={}",
                    canalProperties.getHost(),
                    canalProperties.getPort(),
                    canalProperties.getDestination(),
                    canalProperties.getFilter(),
                    canalProperties.getTopic()
            );

            while (running) {
                Message message = connector.getWithoutAck(Math.max(canalProperties.getBatchSize(), 1));
                long batchId = message.getId();
                if (batchId == -1L || message.getEntries() == null || message.getEntries().isEmpty()) {
                    sleepQuietly(canalProperties.getIntervalMs());
                    continue;
                }

                try {
                    publishBatch(message);
                    connector.ack(batchId);
                } catch (Exception ex) {
                    log.warn("Canal bridge publish failed, batchId={}", batchId, ex);
                    connector.rollback(batchId);
                    sleepQuietly(canalProperties.getIntervalMs());
                }
            }
        } catch (Exception ex) {
            log.error("Canal bridge stopped by error", ex);
        } finally {
            disconnectQuietly();
        }
    }

    private void publishBatch(Message message) throws Exception {
        for (CanalEntry.Entry entry : message.getEntries()) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }

            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();
            if (eventType != CanalEntry.EventType.INSERT && eventType != CanalEntry.EventType.UPDATE) {
                continue;
            }

            ArrayNode dataArray = objectMapper.createArrayNode();
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                ObjectNode rowNode = objectMapper.createObjectNode();
                for (CanalEntry.Column column : rowData.getAfterColumnsList()) {
                    rowNode.put(column.getName(), column.getValue());
                }
                dataArray.add(rowNode);
            }

            if (dataArray.isEmpty()) {
                continue;
            }

            ObjectNode messageNode = objectMapper.createObjectNode();
            messageNode.put("table", entry.getHeader().getTableName());
            messageNode.put("type", eventType == CanalEntry.EventType.INSERT ? "INSERT" : "UPDATE");
            messageNode.set("data", dataArray);
            kafkaTemplate.send(canalProperties.getTopic(), objectMapper.writeValueAsString(messageNode));
        }
    }

    @Override
    public void stop() {
        running = false;
        disconnectQuietly();
        executorService.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return canalProperties.isEnabled();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(millis, 50L));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void disconnectQuietly() {
        CanalConnector current = connector;
        connector = null;
        if (current == null) {
            return;
        }
        try {
            current.disconnect();
        } catch (Exception ex) {
            log.warn("Canal disconnect failed: {}", ex.getMessage());
        }
    }
}
