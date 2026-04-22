package com.zhiguang.be.trade.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.trade.service.impl.TradeServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * 交易下单消费者。
 * 负责把 Kafka 中的下单事件真正落成数据库订单。
 */
@Service
@ConditionalOnProperty(name = "trade.kafka.enabled", havingValue = "true")
public class TradeOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeOrderConsumer.class);

    private final ObjectMapper objectMapper;
    private final TradeServiceImpl tradeService;

    /**
     * 注入交易服务与 JSON 组件。
     */
    public TradeOrderConsumer(ObjectMapper objectMapper, TradeServiceImpl tradeService) {
        this.objectMapper = objectMapper;
        this.tradeService = tradeService;
    }

    /**
     * 消费下单事件。
     * 只有业务处理完成后才提交位点。
     */
    @KafkaListener(
            topics = TradeTopics.ORDER_EVENTS,
            groupId = "trade-order",
            containerFactory = "tradeManualAckListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        TradeOrderEvent event = objectMapper.readValue(message, TradeOrderEvent.class);
        try {
            tradeService.acceptOrderEvent(event);
            acknowledgment.acknowledge();
        } catch (Exception ex) {
            log.warn("consume trade order event failed, orderNo={}", event.orderNo(), ex);
        }
    }
}
