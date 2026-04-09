package com.fanshop.messaging;

import java.util.function.Consumer;

import com.fanshop.messaging.event.InventoryRejectedEvent;
import com.fanshop.messaging.event.InventoryReservedEvent;
import com.fanshop.messaging.event.PaymentCompletedEvent;
import com.fanshop.messaging.event.PaymentFailedEvent;
import com.fanshop.order.service.OrderService;

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
    public Consumer<InventoryReservedEvent> inventoryReservedConsumer() {
        return event -> {
            log.info("Received inventory.reserved — orderId={}", event.orderId());
            orderService.waitForPayment(event.orderId());
        };
    }

    @Bean
    public Consumer<InventoryRejectedEvent> inventoryRejectedConsumer() {
        return event -> {
            log.info("Received inventory.rejected — orderId={}, reason={}", event.orderId(), event.reason());
            orderService.cancelOrder(event.orderId(), event.reason());
        };
    }

    @Bean
    public Consumer<PaymentCompletedEvent> paymentCompletedConsumer() {
        return event -> {
            log.info("Received payment.completed — orderId={}", event.orderId());
            orderService.confirmOrder(event.orderId());
        };
    }

    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return event -> {
            log.info("Received payment.failed — orderId={}, reason={}", event.orderId(), event.reason());
            orderService.cancelOrder(event.orderId(), event.reason());
        };
    }

}
