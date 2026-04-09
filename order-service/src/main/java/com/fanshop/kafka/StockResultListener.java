package com.fanshop.kafka;

import com.fanshop.kafka.event.StockResultEvent;
import com.fanshop.order.service.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockResultListener {

    private final OrderService orderService;

    @KafkaListener(topics = "stock.result", groupId = "order-service")
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
