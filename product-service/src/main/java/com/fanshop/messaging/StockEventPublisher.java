package com.fanshop.messaging;

import com.fanshop.messaging.event.StockResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockEventPublisher {

    private static final String STOCK_RESULT_BINDING = "stockResult-out-0";

    private final StreamBridge streamBridge;

    public void publishStockResult(StockResultEvent event) {
        streamBridge.send(STOCK_RESULT_BINDING, event);
        log.info("Published stock.result: orderId={}, success={}", event.orderId(), event.success());
    }

}
