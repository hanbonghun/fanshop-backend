package com.fanshop.kafka;

import com.fanshop.kafka.event.StockResultEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockEventPublisher {

    private static final String STOCK_RESULT_TOPIC = "stock.result";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishStockResult(StockResultEvent event) {
        kafkaTemplate.send(STOCK_RESULT_TOPIC, String.valueOf(event.orderId()), event);
        log.info("Published stock.result: orderId={}, success={}", event.orderId(), event.success());
    }

}
