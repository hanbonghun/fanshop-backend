package com.fanshop.messaging;

import com.fanshop.messaging.event.StockResultEvent;
import com.fanshop.order.service.OrderService;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class StockResultListener {

    private final OrderService orderService;

    @Bean
    public Consumer<StockResultEvent> stockResultConsumer() {
        return this::handleStockResult;
    }

    public void handleStockResult(StockResultEvent event) {
        log.info("Received stock.result: orderId={}, success={}", event.orderId(), event.success());
        if (event.success()) {
            orderService.confirmOrder(event.orderId());
        }
        else {
            orderService.cancelOrder(event.orderId(), event.reason());
        }
    }

}
